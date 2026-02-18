package altitude.core.controller

import altitude.core.{Altitude, App}
import altitude.core.suites.SqliteSuiteBundle
import altitude.test.{TestContext, TestFocus}
import io.undertow.Undertow
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, funsuite}

class ControllerTestCore
  extends funsuite.AnyFunSuite
    with  BeforeAndAfterAll
    with BeforeAndAfterEach
    with TestFocus {

  val testApp: Altitude = SqliteSuiteBundle.testApp

  var testContext: TestContext = new TestContext(testApp)

  override def beforeEach(): Unit = {
    testContext = new TestContext(testApp)

    // The database is dirtied by the separate process (test server)
    // so we need to reset it before each test.
    // This also sets the test instance as "initialized"
    SqliteSuiteBundle.setup()
  }

  def uninitializeInstance(): Any = {
    testApp.isInitialized = false
    testApp.txManager.withTransaction {
      testApp.DAO.systemMetadata.setUninitialized()
    }
  }

  def login(): Unit = {
    withServer(App) { host =>
      val loginResponse = requests.post(
        s"$host/login",
        maxRedirects = 0,
        check = false,
        data = Map(
          "login" -> testContext.user.email,
          "password" -> TestContext.USER_PASSWORD,
        ))

      loginResponse.statusCode shouldBe 302

      testContext.cookies = loginResponse.cookies
    }
  }

  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(example.defaultHandler)
      .build

    server.start()

    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

}

