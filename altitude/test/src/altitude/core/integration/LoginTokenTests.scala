package altitude.core.integration

import altitude.test.TestContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Instant
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ a, be, should, shouldBe }
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.IllegalOperationException
import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.TokenClaims
import altitude.core.models.User
import altitude.core.routes.web.SessionController
import altitude.core.service.PasetoService
import altitude.core.service.RepositoryService
import altitude.core.service.UserService.TokenCheck

@DoNotDiscover class LoginTokenTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addLibrary(): Repository = {
    val library = testApp.service.repository.addRepository(TestContext.nextRepositoryName(), C.StorageEngineName.FS)
    testApp.service.repository.switchContextToRepository(testContext.repository)
    library
  }

  /** The token a login by the account's username mints */
  private def tokenOf(account: User): String =
    testApp.service.user.loginAndSetUser(account.username, TestContext.USER_PASSWORD).get._2

  private def claimsOf(token: String): TokenClaims = testApp.service.paseto.readToken(token).get

  private def versionOf(account: User): Long = testApp.service.accountVersions.current(account.persistedId)

  private def setActive(account: User, active: Boolean): Unit =
    testApp.txManager.withTransaction {
      update("UPDATE account SET is_active = ? WHERE id = ?", active, account.persistedId)
    }

  test("A login token carries the account, its Create Libraries flag, its grants and its version") {

    /**
     * Setup:
     *
     * A User with the Create Libraries flag, granted Import in one Library and every Permission in another, and an Admin.
     *
     * Assertions:
     *
     * The User's login token reads back with its ID, email, username, name, account type and flag, a grant per Library with its
     * Permissions, and the account's current version. Its Permissions in a Library are the grant's, and none where it holds no
     * grant. The Admin's token carries no grant, and gives it every Permission in any Library.
     */
    val user = testContext.persistUser(Some(testContext.makeUser().copy(canCreateLibraries = true)))
    val importOnly = addLibrary()
    val everything = addLibrary()
    testContext.grant(user, importOnly, Permissions.of(Permission.Import))
    testContext.grant(user, everything)

    val claims = claimsOf(tokenOf(user))
    claims.account shouldBe user.copy(lastLoginAt = None)
    claims.account.canCreateLibraries shouldBe true
    claims.grants shouldBe Map(
      importOnly.persistedId -> Permissions.of(Permission.Import),
      everything.persistedId -> Permissions.all)
    claims.version shouldBe versionOf(user)
    claims.permissionsIn(importOnly.persistedId) shouldBe Some(Permissions.of(Permission.Import))
    claims.permissionsIn(testContext.repository.persistedId) shouldBe None

    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val adminClaims = claimsOf(tokenOf(admin))
    adminClaims.grants shouldBe Map.empty
    adminClaims.permissionsIn(importOnly.persistedId) shouldBe Some(Permissions.all)
  }

  test("The token's grants are what the gate reads: a grant removed behind the version map still shows in it") {

    /**
     * Setup:
     *
     * A User granted every Permission in a Library and logged in; the grant is then deleted by hand-written SQL, which bumps no
     * version.
     *
     * Assertions:
     *
     * The token still checks as current and still gives the Permissions in the Library, since nothing on a request reads the
     * database.
     */
    val user = testContext.persistUser()
    val library = addLibrary()
    testContext.grant(user, library)
    val token = tokenOf(user)

    testApp.txManager.withTransaction(update("DELETE FROM repository_grant WHERE account_id = ?", user.persistedId))

    testApp.service.user.checkToken(token, renewable = true) match {
      case TokenCheck.Current(claims) => claims.permissionsIn(library.persistedId) shouldBe Some(Permissions.all)
      case other => fail(s"Expected the token to be current, got $other")
    }
  }

  test("A token below the account's version is rebuilt from the database, keeping its expiry") {

    /**
     * Setup:
     *
     * A User granted Import in a Library and logged in, then granted every Permission in a second Library, which raises the
     * account's version.
     *
     * Assertions:
     *
     * The token checks as stale and is rebuilt: the new claims carry both grants and the current version, and expire when the
     * first token does. The rebuilt token checks as current.
     */
    val user = testContext.persistUser()
    val first = addLibrary()
    val second = addLibrary()
    testContext.grant(user, first, Permissions.of(Permission.Import))
    val token = tokenOf(user)
    val before = claimsOf(token)

    testContext.grant(user, second)
    versionOf(user) should be > before.version

    testApp.service.user.checkToken(token, renewable = true) match {
      case TokenCheck.Renewed(claims, renewed) =>
        claims.grants shouldBe Map(first.persistedId -> Permissions.of(Permission.Import), second.persistedId -> Permissions.all)
        claims.version shouldBe versionOf(user)
        claims.expiresAt shouldBe before.expiresAt
        claimsOf(renewed) shouldBe claims
        testApp.service.user.checkToken(renewed, renewable = true) shouldBe TokenCheck.Current(claims)
      case other => fail(s"Expected the token to be renewed, got $other")
    }
  }

  test("A stale token of a deactivated or deleted account is refused") {

    /**
     * Setup:
     *
     * Two logged-in Users: the first is deactivated, the second deleted, each by hand-written SQL, and each account's version is
     * raised, as the account writes do after their commit.
     *
     * Assertions:
     *
     * Both tokens are refused, renewable as they are, and the refusal clears the session cookie: a rebuild finds no active
     * account, and the token will never be good again.
     *
     * Edge cases:
     *
     * Before its version is raised, the deactivated account's token is still current: the gate reads no database.
     */
    val deactivated = testContext.persistUser()
    val deleted = testContext.persistUser()
    val deactivatedToken = tokenOf(deactivated)
    val deletedToken = tokenOf(deleted)

    setActive(deactivated, active = false)
    testApp.service.user.checkToken(deactivatedToken, renewable = true) shouldBe a[TokenCheck.Current]
    testApp.service.accountVersions.bump(deactivated.persistedId)

    testApp.txManager.withTransaction(update("DELETE FROM account WHERE id = ?", deleted.persistedId))
    testApp.service.accountVersions.bump(deleted.persistedId)

    testApp.service.user.checkToken(deactivatedToken, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = true)
    testApp.service.user.checkToken(deletedToken, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = true)
  }

  test("A token below the revoked-below version is refused; one minted after the revocation is current") {

    /**
     * Setup:
     *
     * A logged-in User whose tokens are then revoked, as a password change does; then a second User whose tokens are revoked and
     * who is deactivated, by hand-written SQL.
     *
     * Assertions:
     *
     * The earlier token is refused, renewable as it is, and a token from a new login checks as current. The refusal leaves the
     * session cookie alone: the browser may hold the token the change minted by now (a request in flight while the account
     * changed its own password), which a cleared cookie would log out.
     *
     * Edge cases:
     *
     * The deactivated User's revoked token is refused clearing the cookie, as a token of an account that can do nothing is.
     */
    val user = testContext.persistUser()
    val token = tokenOf(user)

    testApp.service.accountVersions.revoke(user.persistedId)

    testApp.service.user.checkToken(token, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = false)
    testApp.service.user.checkToken(tokenOf(user), renewable = true) shouldBe a[TokenCheck.Current]

    val deactivated = testContext.persistUser()
    val deactivatedToken = tokenOf(deactivated)
    testApp.service.accountVersions.revoke(deactivated.persistedId)
    setActive(deactivated, active = false)
    testApp.service.user.checkToken(deactivatedToken, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = true)
  }

  test("A stale token that cannot be replaced, a Bearer token's, is refused") {

    /**
     * Setup:
     *
     * A logged-in User whose version is then raised.
     *
     * Assertions:
     *
     * The token, checked as one the server cannot replace, is refused, with no cookie to clear; checked before the raise, it was
     * current.
     */
    val user = testContext.persistUser()
    val token = tokenOf(user)
    testApp.service.user.checkToken(token, renewable = false) shouldBe a[TokenCheck.Current]

    testApp.service.accountVersions.bump(user.persistedId)

    testApp.service.user.checkToken(token, renewable = false) shouldBe TokenCheck.Refused(clearsCookie = false)
  }

  test("A token that is not one the server minted is refused") {

    /**
     * Setup:
     *
     * A logged-in User's token, and the same token with one character in the middle of its ciphertext changed.
     *
     * Assertions:
     *
     * The changed token and an empty one are refused, and the refusal clears the session cookie.
     */
    val token = tokenOf(testContext.persistUser())
    // The middle character carries 6 bits of the ciphertext, where the last may carry only padding
    val middle = token.length / 2
    val tampered = token.updated(middle, if token(middle) == 'A' then 'B' else 'A')

    testApp.service.user.checkToken(tampered, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = true)
    testApp.service.user.checkToken("", renewable = true) shouldBe TokenCheck.Refused(clearsCookie = true)
  }

  test("Granting past the bound is refused with a message; a held grant at the bound still changes") {

    /**
     * Setup:
     *
     * A User granted Import in as many Libraries as the bound allows, and one Library more.
     *
     * Assertions:
     *
     * A grant in the extra Library fails with `IllegalOperationException`, saying the bound, and writes nothing. A grant set
     * again in a Library the User holds one in replaces its Permissions.
     */
    val user = testContext.persistUser()
    val libraries = List.fill(RepositoryService.MAX_GRANTS_PER_ACCOUNT)(addLibrary())
    for (library <- libraries) testContext.grant(user, library, Permissions.of(Permission.Import))
    val extra = addLibrary()

    val refused = intercept[IllegalOperationException](testContext.grant(user, extra))
    refused.getMessage shouldBe C.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT)
    testContext.grantOf(user, extra.persistedId) shouldBe None

    testContext.grant(user, libraries.head)
    testContext.grantOf(user, libraries.head.persistedId) shouldBe Some(Permissions.all)
  }

  test("Two grants racing at the bound leave the account at the bound") {

    /**
     * Setup:
     *
     * A User granted access to one Library fewer than the bound allows, and two more Libraries, granted from two transactions at
     * once: the first holds its transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second grant fails with `IllegalOperationException` once the first commits, and the User holds as many grants as the
     * bound allows. On PostgreSQL the account's row lock decides it: without it, both transactions count one grant fewer than the
     * bound.
     */
    val user = testContext.persistUser()
    for (_ <- 1 until RepositoryService.MAX_GRANTS_PER_ACCOUNT) testContext.grant(user, addLibrary())
    val first = addLibrary()
    val second = addLibrary()

    intercept[IllegalOperationException] {
      contend(first = testContext.grant(user, first), second = testContext.grant(user, second))
    }

    testApp.txManager.asReadOnly {
      query("SELECT repository_id FROM repository_grant WHERE account_id = ?", user.persistedId).size
    } shouldBe RepositoryService.MAX_GRANTS_PER_ACCOUNT
  }

  test("A token at the grant bound fits the session cookie") {

    /**
     * Setup:
     *
     * The longest account a token describes: a name of 80 four-byte characters, an 80-character email address and a 32-character
     * username, holding every Permission in as many Libraries as the bound allows.
     *
     * Assertions:
     *
     * The session cookie's name, value and attributes, as the login sets them, fit the 4096 bytes a browser keeps of a cookie.
     */
    val longest = User(
      id = Some(java.util.UUID.randomUUID.toString),
      email = s"${"e" * 68}@example.com",
      username = "u" * 32,
      name = "😀" * 80,
      accountType = AccountType.User,
      canCreateLibraries = true
    )
    longest.email.length shouldBe 80
    val grants = List.fill(RepositoryService.MAX_GRANTS_PER_ACCOUNT)(java.util.UUID.randomUUID.toString -> Permissions.all).toMap
    val claims = TokenClaims(longest, grants, version = Long.MaxValue, expiresAt = Instant.now.plusSeconds(60 * 60 * 24 * 7))

    val token = testApp.service.paseto.createToken(claims)
    val cookie = SessionController.authCookie(token, claims.expiresAt)
    // The attributes as Undertow writes them, an Expires date included
    val attributes =
      s"; Path=${cookie.path}; Max-Age=${cookie.maxAge}; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict"
    val cookieBytes = s"${cookie.name}=${cookie.value}$attributes".length

    withClue(s"A token of [${token.length}] bytes makes a cookie of [$cookieBytes] bytes: ") {
      cookieBytes should be <= 4096
    }
  }

  test("A token rebuilt at the version read before a revocation is refused once the revocation lands") {

    /**
     * Setup:
     *
     * A logged-in User whose version is raised, so its token is stale; the account's versions are read, then its tokens revoked,
     * and the stale token is rebuilt at the versions read before the revocation.
     *
     * Assertions:
     *
     * The rebuilt token is refused: it is minted at the version read with the standing, never at one read after it, so a
     * revocation that lands between the two cannot pass for one the token postdates.
     */
    val user = testContext.persistUser()
    val token = tokenOf(user)
    testApp.service.accountVersions.bump(user.persistedId)

    val snapshot = testApp.service.accountVersions.snapshot(user.persistedId)
    testApp.service.accountVersions.revoke(user.persistedId)
    val (_, rebuilt) = testApp.service.user.renew(claimsOf(token), snapshot).get

    testApp.service.user.checkToken(rebuilt, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = false)
  }

  test("A token longer than the cookie's budget is logged at ERROR") {

    /**
     * Setup:
     *
     * Claims of an ordinary account with grants in 80 Libraries, more than the bound allows, and claims of the longest account at
     * the bound.
     *
     * Assertions:
     *
     * Minting the first logs an ERROR naming its length, since a browser would drop its cookie; minting the second logs none.
     */
    val pasetoLogger = LoggerFactory.getLogger(classOf[PasetoService]).asInstanceOf[Logger]
    val capture = ListAppender[ILoggingEvent]()
    // Test logging is off, so the level is raised for the test
    val level = pasetoLogger.getLevel
    capture.start()
    pasetoLogger.addAppender(capture)
    pasetoLogger.setLevel(Level.ERROR)
    def grants(n: Int) = List.fill(n)(java.util.UUID.randomUUID.toString -> Permissions.all).toMap
    val expiresAt = Instant.now.plusSeconds(3600)

    try {
      testApp.service.paseto.createToken(TokenClaims(testContext.makeUser().copy(id = Some("a")), grants(80), 1, expiresAt))
      capture.list.asScala.count(_.getLevel == Level.ERROR) shouldBe 1

      capture.list.clear()
      val longest = User(
        id = Some(java.util.UUID.randomUUID.toString),
        email = s"${"e" * 68}@example.com",
        username = "u" * 32,
        name = "\uD83D\uDE00" * 80,
        accountType = AccountType.User)
      testApp.service.paseto.createToken(
        TokenClaims(longest, grants(RepositoryService.MAX_GRANTS_PER_ACCOUNT), Long.MaxValue, expiresAt))
      capture.list.asScala.count(_.getLevel == Level.ERROR) shouldBe 0
    } finally {
      pasetoLogger.detachAppender(capture)
      pasetoLogger.setLevel(level)
    }
  }
}
