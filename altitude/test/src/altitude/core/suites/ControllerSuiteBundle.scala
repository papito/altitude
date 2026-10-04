package altitude.core.suites

import altitude.test.{ IntegrationTestUtil, TestAltitudeApp }
import org.scalatest.BeforeAndAfterAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Altitude, RequestContext }

// Why is this Postgres and not both?
// See: https://github.com/papito/altitude/wiki/How-the-tests-work#controller-tests-and-the-forced-postgres-config
class ControllerSuiteBundle extends AllControllerTestSuites() with BeforeAndAfterAll {

  final protected val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@")
    println("CONTROLLER TESTS")
    println("@@@@@@@@@@@@@@@@\n")
  }
}
