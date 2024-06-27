package software.altitude.test.core

import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite
import org.scalatra.test.ScalatraTests
import org.scalatra.test.scalatest.ScalatraFunSuite
import software.altitude.core.Altitude
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Environment
import software.altitude.test.core.integration.TestContext
import software.altitude.test.core.suites.PostgresSuite
import software.altitude.test.core.suites.PostgresSuiteSetup

abstract class WebTestCore
  extends funsuite.AnyFunSuite
  with ScalatraTests
  with ScalatraFunSuite
  with BeforeAndAfter
  with BeforeAndAfterEach
  with TestFocus {

  override def header = null

  // mount all controllers, just as we do in ScalatraBootstrap
  AltitudeServletContext.endpoints.foreach { case (servlet, path) =>
    mount(servlet, path)
  }

  Environment.ENV = Environment.TEST

  override def beforeEach(): Unit = {
    // the database is dirtied by the separate process (test server)
    // so we need to reset it before each test
    PostgresSuiteSetup.setup()

    // the few application state variables should also be rolled back
    AltitudeServletContext.app.isInitialized = false
  }

  protected def altitude: Altitude = PostgresSuite.app

  var testContext: TestContext = new TestContext(altitude)

}
