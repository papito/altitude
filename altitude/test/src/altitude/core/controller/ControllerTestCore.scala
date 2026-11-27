package altitude.core.controller

import altitude.test.{ TestContext, TestFocus }
import io.undertow.Undertow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpCookie
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import org.scalatest.{ funsuite, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.Using

import altitude.core.{ Altitude, App, RequestContext }
import altitude.core.Const as C
import altitude.core.models.{ AccountType, Permissions, Repository, User }
import altitude.core.suites.SqliteSuiteBundle
import altitude.core.util.Util

class ControllerTestCore extends funsuite.AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with TestFocus {

  val testApp: Altitude = SqliteSuiteBundle.testApp

  var testContext: TestContext = new TestContext(testApp)

  override def beforeEach(): Unit = {
    testContext = new TestContext(testApp)
    // The fixtures the test builds through the suite's instance act with every Permission; the server's requests read theirs
    // from the login token
    RequestContext.permissions.value = Permissions.all

    // The server's requests write to the shared database, so it is reset before each test; this also marks it initialized
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
   * Logs the test context's account in. A User or Guest with no grant in the context's first Library is granted it first, every
   * Permission for a User and none for a Guest; a test that wants fewer grants them before logging in, or uses [[loginAs]].
   */
  def login(): Unit = {
    val account = testContext.user
    for (repository <- testContext.repositories.headOption if account.accountType != AccountType.Admin) {
      if (testContext.grantOf(account, repository.persistedId).isEmpty) {
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
   * Logs in a new account of `accountType` holding `permissions` in a new Library, and answers the Library's ID. An Admin holds
   * every Permission by type and a Guest none, so for them `permissions` must be all or none.
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

  /** A new Library under `name` that `account` can see: a User with every Permission, a Guest with none */
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

  /**
   * Whether `f` updates the account's row. While it runs, a trigger stamps the row's `updated_at`, cleared beforehand, on any
   * update of it, whatever the update sets. The trigger is SQLite's, the engine of this suite, and named for the call alone.
   */
  def writesAccount(user: User)(f: => Unit): Boolean = {
    assume(testApp.dataSourceType == C.DbEngineName.SQLITE, "The trigger is SQLite's")
    def run(sql: String, values: List[Any] = Nil): Unit =
      testApp.txManager.withTransaction(testApp.DAO.user.updateByBySql(sql, values): Unit)
    val trigger = s"account_written_${Util.randomStr().toLowerCase}"

    run("UPDATE account SET updated_at = NULL WHERE id = ?", List(user.persistedId))
    run(
      s"CREATE TRIGGER $trigger AFTER UPDATE ON account BEGIN " +
        "UPDATE account SET updated_at = datetime('now') WHERE id = NEW.id; END")
    try f
    finally run(s"DROP TRIGGER $trigger")

    testApp.txManager.asReadOnly {
      testApp.DAO.user.manyBySqlQuery("SELECT updated_at FROM account WHERE id = ?", List(user.persistedId)).head("updated_at")
    } != null
  }

  /**
   * Loads `path`, a full page of `repo`, with `other` recorded as the user's last active Library beforehand: the page answers 200
   * and records `repo`, a write of the account, and a reload of it writes nothing
   */
  def assertPageRecords(host: String, user: User, path: String, repo: Repository, other: Repository): Unit =
    withClue(s"[$path]: ") {
      def load(): Unit = requests.get(s"$host/$path", cookies = testContext.cookies, check = false).statusCode shouldBe 200

      testApp.service.user.rememberLastActiveRepository(user, other.persistedId)
      writesAccount(user)(load()) shouldBe true
      lastActiveRepoIdOf(user) shouldBe Some(repo.persistedId)

      writesAccount(user)(load()) shouldBe false
      lastActiveRepoIdOf(user) shouldBe Some(repo.persistedId)
    }

  /**
   * Runs `f` against `app` served at the address it is given. With `requestThreads`, the server answers on that many threads:
   * with one, every request runs on the thread the request before it ran on.
   */
  /**
   * Posts over a socket of the test's own and answers the status the server sends, if it sends one, read while the body is still
   * going out. A server that refuses a body it has not read answers and closes before the client is done, which an HTTP client
   * reports as a broken connection, not as the answer.
   *
   * @param declaredLength
   *   the `Content-Length` the request states, which need not be what is sent; none sends the body chunked
   * @param body
   *   what is sent of the body, in pieces of 8 KB, a chunk each when the body is chunked
   */
  def rawPostStatus(
      host: String,
      path: String,
      contentType: String,
      body: Array[Byte],
      declaredLength: Option[Long]): Option[Int] = {
    val address = URI.create(host)
    Using.resource(new Socket(address.getHost, address.getPort)) {
      socket =>
        socket.setSoTimeout(30000)
        val status = Future {
          val line = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII)).readLine()
          Option(line).flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption)
        }(using ExecutionContext.global)

        def ascii(text: String): Array[Byte] = text.getBytes(StandardCharsets.US_ASCII)
        val framing = declaredLength.fold("Transfer-Encoding: chunked")(length => s"Content-Length: $length")
        val out = socket.getOutputStream
        try {
          out.write(
            ascii(s"POST $path HTTP/1.1\r\nHost: ${address.getAuthority}\r\nContent-Type: $contentType\r\n$framing\r\n\r\n"))
          body.grouped(8192).foreach {
            piece =>
              if (declaredLength.isEmpty) out.write(ascii(s"${piece.length.toHexString}\r\n"))
              out.write(piece)
              if (declaredLength.isEmpty) out.write(ascii("\r\n"))
              out.flush()
          }
          if (declaredLength.isEmpty) out.write(ascii("0\r\n\r\n"))
          out.flush()
        } catch {
          // The server has closed its side, having answered
          case _: IOException => ()
        }
        Try(Await.result(status, 30.seconds)).toOption.flatten
    }
  }

  def withServer[T](app: cask.main.Main, requestThreads: Option[Int] = None)(f: String => T): T = {
    val builder = Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(app.defaultHandler)
    requestThreads.foreach(builder.setWorkerThreads)
    val server = builder.build

    server.start()

    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

}
