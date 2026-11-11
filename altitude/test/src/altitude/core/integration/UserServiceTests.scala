package altitude.core.integration

import altitude.core
import altitude.test.TestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.empty
import org.scalatest.matchers.should.Matchers.should
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.matchers.should.Matchers.shouldEqual

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.User
import altitude.core.util.NameKey
import altitude.core.util.Util

@DoNotDiscover class UserServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("An account reads back as added, its email address and username stored beside their name keys") {

    /**
     * Setup:
     *
     * A User added with an email address and a username in mixed case, the username with a full-width letter, created by the
     * common setup's account, with the Create Libraries flag and the new-Library grant of Import and Curate own.
     *
     * Assertions:
     *
     * The account reads back by its ID with every field as added: active, never logged in, and with no last active Library. The
     * stored `email_key` and `username_key` are the name keys (`NameKey.of`) of the address and the username.
     */
    val creator = testContext.users.head
    val model = testContext
      .makeUser()
      .copy(
        email = s"Jane.${Util.randomStr(8)}@Example.com",
        username = s"\uFF2Aane.${Util.randomStr(8)}",
        canCreateLibraries = true,
        grantsNewLibraries = true,
        newLibraryPermissions = Permissions.of(Permission.Import, Permission.CurateOwn),
        createdByAccountId = Some(creator.persistedId)
      )
    val user: User = testContext.persistUser(Some(model))

    testApp.service.user.getById(user.persistedId) shouldEqual model.copy(id = user.id)
    user.isActive shouldBe true
    user.lastLoginAt shouldBe None

    val keys = testApp.txManager.asReadOnly {
      query("SELECT email_key, username_key FROM account WHERE id = ?", user.persistedId).head
    }
    keys("email_key") shouldBe NameKey.of(model.email)
    keys("username_key") shouldBe NameKey.of(model.username)
  }

  test("Remembering a Library writes the account only when it is not the one recorded") {

    /**
     * Setup:
     *
     * A new User with no last active Library, the common setup's Library and a second one.
     *
     * Assertions:
     *
     * Remembering the first Library records it, reports a write and stamps the account's update time. Remembering it again
     * reports no write and leaves the update time as it was (cleared beforehand, so a write would show). Remembering the second
     * Library records it and reports a write.
     */
    val user: User = testContext.persistUser()
    val first = testContext.repository.persistedId
    val second = testContext.persistRepository().persistedId

    def updatedAt: Option[AnyRef] = testApp.txManager.asReadOnly {
      Option(query("SELECT updated_at FROM account WHERE id = ?", user.persistedId).head("updated_at"))
    }
    def lastActive: Option[String] = testApp.service.user.getById(user.persistedId).lastActiveRepoId

    testApp.service.user.rememberLastActiveRepository(user, first) shouldBe true
    lastActive shouldBe Some(first)
    updatedAt should not be empty

    testApp.txManager.withTransaction(update("UPDATE account SET updated_at = NULL WHERE id = ?", user.persistedId))
    testApp.service.user.rememberLastActiveRepository(user, first) shouldBe false
    lastActive shouldBe Some(first)
    updatedAt shouldBe None

    testApp.service.user.rememberLastActiveRepository(user, second) shouldBe true
    lastActive shouldBe Some(second)
  }

  test("Check valid user password") {

    /**
     * Setup:
     *
     * A regular user with a known password.
     *
     * Assertions:
     *
     * Logging in with the user's email and that password succeeds and yields that user and a non-empty session token.
     */
    val password = "MyPassword123"

    val userModel = testContext.makeUser()

    val created: User = testContext.persistUser(Some(userModel), password = password)

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, password)

    loginResult match {
      case Some((user, token)) =>
        user.persistedId shouldEqual created.persistedId
        token should not be empty
      case None =>
        fail("Login failed: user or token is None")
    }
  }

  test("An email address names one account, whatever its case or Unicode form") {

    /**
     * Setup:
     *
     * A user whose email has upper-case letters, then second users with the same email in lower case and with its first letter
     * full-width.
     *
     * Assertions:
     *
     * Each second user is refused as a duplicate, and the first logs in with the email in either case or with the full-width
     * letter.
     */
    val email = s"Mixed.Case.${Util.randomStr(8)}@Example.com"
    val fullWidth = "\uFF2D" + email.drop(1)
    val user: User = testContext.persistUser(Some(testContext.makeUser().copy(email = email)))

    for (taken <- List(email.toLowerCase, fullWidth)) withClue(taken) {
      intercept[DuplicateException] {
        testApp.service.user.add(testContext.makeUser().copy(email = taken), password = TestContext.USER_PASSWORD)
      }
    }

    for (spelling <- List(email, email.toLowerCase, email.toUpperCase, fullWidth)) withClue(spelling) {
      testApp.service.user.loginAndSetUser(spelling, TestContext.USER_PASSWORD).map(_._1.persistedId) shouldBe
        Some(user.persistedId)
    }
  }

  test("A username names one account, whatever its case or Unicode form") {

    /**
     * Setup:
     *
     * A user named "Jane.Doe" (under a random suffix), then second users of other email addresses named "jane.doe" and with a
     * full-width "Ｊ".
     *
     * Assertions:
     *
     * Each second user is refused as a duplicate, and only the first holds the username.
     */
    val username = s"Jane.Doe.${Util.randomStr(8)}"
    testContext.persistUser(username = Some(username))

    for (taken <- List(username.toLowerCase, "\uFF2A" + username.drop(1))) withClue(taken) {
      intercept[DuplicateException] {
        testApp.service.user.add(testContext.makeUser().copy(username = taken), password = TestContext.USER_PASSWORD)
      }
    }

    testApp.txManager.asReadOnly {
      query("SELECT id FROM account WHERE username_key = ?", NameKey.of(username)).size
    } shouldBe 1
  }

  test("Two transactions adding one username at once leave one account") {

    /**
     * Setup:
     *
     * Two adds of an account under one username, in other capitals and of other email addresses, from two transactions: the first
     * holds its transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second add fails with a `DuplicateException` once the first commits, and one account holds the username, the first's,
     * as it spelled it. The database decides it: on PostgreSQL both transactions insert before either commits.
     */
    val username = s"Race.${Util.randomStr(8)}"

    intercept[DuplicateException] {
      contend(
        first = testApp.service.user.add(testContext.makeUser().copy(username = username), TestContext.USER_PASSWORD): Unit,
        second =
          testApp.service.user.add(testContext.makeUser().copy(username = username.toUpperCase), TestContext.USER_PASSWORD): Unit
      )
    }

    testApp.txManager.asReadOnly {
      query("SELECT username FROM account WHERE username_key = ?", NameKey.of(username)).map(_("username"))
    } shouldBe List(username)
  }

  test("The account rebuilt from a login token carries its username") {

    /**
     * Setup:
     *
     * A User logged in by email and password.
     *
     * Assertions:
     *
     * The account the login's token rebuilds, with no database read, has the User's ID, email, username, name and account type.
     */
    val user = testContext.persistUser()
    val (_, token) = testApp.service.user.loginAndSetUser(user.email, TestContext.USER_PASSWORD).get

    val rebuilt = testApp.service.user.getUserFromToken(token).get
    (rebuilt.persistedId, rebuilt.email, rebuilt.username, rebuilt.name, rebuilt.accountType) shouldBe
      (user.persistedId, user.email, user.username, user.name, user.accountType)
  }

  test("Login fails with invalid password") {

    /**
     * Setup:
     *
     * A regular user with a known password.
     *
     * Assertions:
     *
     * Logging in with the user's email and a different password yields nothing.
     */
    val password = "MyPassword123"
    val wrongPassword = "WrongPassword456"

    val userModel = testContext.makeUser()

    testContext.persistUser(Some(userModel), password = password)

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, wrongPassword)

    loginResult shouldEqual None
  }

  test("Login fails with non-existent user") {

    /**
     * Setup:
     *
     * An email that belongs to no user.
     *
     * Assertions:
     *
     * Logging in with it yields nothing.
     */
    val nonExistentEmail = "nonexistent@example.com"
    val password = "SomePassword123"

    val loginResult = testApp.service.user.loginAndSetUser(nonExistentEmail, password)

    loginResult shouldEqual None
  }

  if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
    test("Logging in does not wait for the SQLite write connection") {

      /**
       * Setup:
       *
       * A regular user with a known password, and a write transaction on a thread of its own that holds SQLite's single write
       * connection until the test releases it.
       *
       * Assertions:
       *
       * While the write connection is held, logging in still completes within a few seconds and succeeds, since a login only
       * reads.
       */
      val password = "MyPassword123"
      val userModel = testContext.makeUser()
      testContext.persistUser(Some(userModel), password = password)

      val writing = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      // A write transaction on a thread of its own holds the one write connection until released
      val writer = new Thread(
        () =>
          testApp.txManager.withTransaction {
            writing.countDown()
            release.await(30, TimeUnit.SECONDS): Unit
          })
      writer.start()

      try {
        writing.await(5, TimeUnit.SECONDS) shouldEqual true
        val login = Future(testApp.service.user.loginAndSetUser(userModel.email, password))
        Await.result(login, 5.seconds) should not be None
      } finally {
        release.countDown()
        writer.join()
      }
    }

    test("Remembering the Library recorded already does not wait for the SQLite write connection") {

      /**
       * Setup:
       *
       * A User whose last active Library is the common setup's, and a write transaction on a thread of its own that holds
       * SQLite's single write connection until the test releases it.
       *
       * Assertions:
       *
       * While the write connection is held, remembering the recorded Library again completes within a few seconds and reports no
       * write, since it only reads the account to find there is nothing to write.
       */
      val user: User = testContext.persistUser()
      val repoId = testContext.repository.persistedId
      testApp.service.user.rememberLastActiveRepository(user, repoId) shouldBe true

      val writing = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      // A write transaction on a thread of its own holds the one write connection until released
      val writer = new Thread(
        () =>
          testApp.txManager.withTransaction {
            writing.countDown()
            release.await(30, TimeUnit.SECONDS): Unit
          })
      writer.start()

      try {
        writing.await(5, TimeUnit.SECONDS) shouldEqual true
        val remember = Future(testApp.service.user.rememberLastActiveRepository(user, repoId))
        Await.result(remember, 5.seconds) shouldBe false
      } finally {
        release.countDown()
        writer.join()
      }
    }
  }
}
