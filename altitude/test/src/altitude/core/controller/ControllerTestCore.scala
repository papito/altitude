package altitude.core.controller

import altitude.core.Altitude
import altitude.core.suites.SqliteSuiteBundle
import altitude.test.{TestContext, TestFocus}
import io.undertow.Undertow
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

    // the database is dirtied by the separate process (test server)
    // so we need to reset it before each test
    SqliteSuiteBundle.setup()
    testApp.app.isInitialized = false
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

