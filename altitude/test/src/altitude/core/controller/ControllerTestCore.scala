package altitude.core.controller

import altitude.test.{ TestContext, TestFocus }
import io.undertow.Undertow
import java.net.HttpCookie
import org.scalatest.{ funsuite, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.{ Altitude, App, RequestContext }
import altitude.core.Const as C
import altitude.core.models.{ AccountType, Permissions, Repository, User }
import altitude.core.suites.SqliteSuiteBundle

class ControllerTestCore extends funsuite.AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with TestFocus {

  val testApp: Altitude = SqliteSuiteBundle.testApp

  var testContext: TestContext = new TestContext(testApp)

  override def beforeEach(): Unit = {
    testContext = new TestContext(testApp)
    // The fixtures the test builds through the suite's instance act with every Permission; the server's requests read theirs
    // from the login token
    RequestContext.permissions.value = Permissions.all

    // The database is dirtied by the separate process (test server)
    // so we need to reset it before each test.
    // This also sets the test instance as "initialized"
    SqliteSuiteBundle.setup()
    // The server's own instance shares the database; its snapshot must see the reseeded rows, not an earlier test's saves
    App.altitude.settings.load()
  }

  def uninitializeInstance(): Any = {
    testApp.isInitialized = false
    testApp.txManager.withTransaction {
      testApp.DAO.systemMetadata.setUninitialized()
    }
  }

  /**
   * Logs the test context's account in. A User or a Guest that holds no grant in the context's first Library is given access to
   * it first, with every Permission for a User, as an Admin has by type; a test that wants fewer grants them before it logs in,
   * or uses [[loginAs]].
   */
  def login(): Unit = {
    val account = testContext.user
    for (repository <- testContext.repositories.headOption if account.accountType != AccountType.Admin) {
      if (testApp.service.repository.getGrant(account, repository.persistedId).isEmpty) {
        val permissions = if (account.accountType == AccountType.Guest) Permissions.none else Permissions.all
        testContext.grant(account, repository, permissions)
      }
    }

    withServer(App)(host => testContext.cookies = cookiesOf(host, account))
  }

  /** The session cookies of `account` logged in, with the test password, to the server at `host` */
  def cookiesOf(host: String, account: User): Map[String, HttpCookie] = {
    val loginResponse = requests.post(
      s"$host/login",
      maxRedirects = 0,
      check = false,
      data = Map(
        "login" -> account.email,
        "password" -> TestContext.USER_PASSWORD
      ))

    loginResponse.statusCode shouldBe 302
    loginResponse.cookies
  }

  /**
   * An account of `accountType`, logged in, and a Library it can see: an Admin by type, holding every Permission with no grant,
   * so `permissions` must be all of them; a User by a grant of `permissions`; a Guest by a grant of none, so `permissions` must
   * be none. The Library's ID.
   */
  def loginAs(accountType: AccountType, permissions: Permissions): String = {
    require(accountType != AccountType.Admin || permissions == Permissions.all, "An Admin holds every Permission")
    require(accountType != AccountType.Guest || permissions.isEmpty, "A Guest holds no Permission")
    val account: User = testContext.persistUser(Some(testContext.makeUser().copy(accountType = accountType)))
    val repository = testContext.persistRepository()
    if (accountType != AccountType.Admin) testContext.grant(account, repository, permissions)
    login()
    repository.persistedId
  }

  /**
   * A Library under `name` that `account` can see: an Admin by type, a User by a grant of every Permission, a Guest by a grant of
   * none
   */
  def addLibrary(name: String, account: User): Repository = {
    val library = testApp.service.repository.addRepository(name = name, fileStoreType = C.StorageEngineName.FS)
    account.accountType match {
      case AccountType.Admin =>
      case AccountType.User => testContext.grant(account, library)
      case AccountType.Guest => testContext.grant(account, library, Permissions.none)
    }
    library
  }

  /** The account's last active Library, as stored */
  def lastActiveRepoIdOf(user: User): Option[String] =
    testApp.service.user.getById(user.persistedId).lastActiveRepoId

  /** Clears the account's update time, which every write of its last active Library stamps, so a write after it shows */
  def clearAccountUpdatedAt(user: User): Unit =
    testApp.txManager.withTransaction {
      testApp.DAO.user.updateByBySql("UPDATE account SET updated_at = NULL WHERE id = ?", List(user.persistedId))
    }

  /** Whether the account was written since [[clearAccountUpdatedAt]] */
  def accountWritten(user: User): Boolean =
    testApp.txManager.asReadOnly {
      testApp.DAO.user.manyBySqlQuery("SELECT updated_at FROM account WHERE id = ?", List(user.persistedId)).head("updated_at")
    } != null

  /**
   * Loads `path`, a full page of `repo`, with `other` recorded as the user's last active Library beforehand: the page answers 200
   * and records `repo`, and a reload of it writes nothing
   */
  def assertPageRecords(host: String, user: User, path: String, repo: Repository, other: Repository): Unit =
    withClue(s"[$path]: ") {
      def load(): Unit = requests.get(s"$host/$path", cookies = testContext.cookies, check = false).statusCode shouldBe 200

      testApp.service.user.rememberLastActiveRepository(user, other.persistedId)
      load()
      lastActiveRepoIdOf(user) shouldBe Some(repo.persistedId)

      clearAccountUpdatedAt(user)
      load()
      accountWritten(user) shouldBe false
      lastActiveRepoIdOf(user) shouldBe Some(repo.persistedId)
    }

  def withServer[T](app: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(app.defaultHandler)
      .build

    server.start()

    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

}
