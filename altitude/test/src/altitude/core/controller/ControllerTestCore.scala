package altitude.core.controller

import altitude.test.{ TestContext, TestFocus }
import io.undertow.Undertow
import org.scalatest.{ funsuite, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.{ Altitude, App }
import altitude.core.Const as C
import altitude.core.models.{ AccountType, Repository, User }
import altitude.core.suites.SqliteSuiteBundle

class ControllerTestCore extends funsuite.AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with TestFocus {

  val testApp: Altitude = SqliteSuiteBundle.testApp

  var testContext: TestContext = new TestContext(testApp)

  override def beforeEach(): Unit = {
    testContext = new TestContext(testApp)

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

  def login(): Unit = {
    withServer(App) {
      host =>
        val loginResponse = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map(
            "login" -> testContext.user.email,
            "password" -> TestContext.USER_PASSWORD
          ))

        loginResponse.statusCode shouldBe 302

        testContext.cookies = loginResponse.cookies
    }
  }

  /** A repository owned by an account of `accountType`, logged in; its ID */
  def loginAs(accountType: AccountType): String = {
    val model = if (accountType == AccountType.Admin) testContext.makeAdminUser() else testContext.makeUser()
    val account: User = testContext.persistUser(Some(model.copy(accountType = accountType)))
    testContext.persistRepository(Some(account))
    login()
    testContext.repository.persistedId
  }

  /** A Library owned by `owner`, under `name` */
  def addLibrary(name: String, owner: User): Repository =
    testApp.service.repository.addRepository(name = name, fileStoreType = C.StorageEngineName.FS, owner = owner)

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
