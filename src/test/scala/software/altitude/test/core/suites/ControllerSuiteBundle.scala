package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.test.core.testAltitudeApp

/*
 The web suite does NOT run against all DB engines. It only runs against Postgres..
 */
class ControllerSuiteBundle extends AllControllerTestSuites(testApp = PostgresSuiteBundle.testApp)
  with testAltitudeApp with BeforeAndAfterAll {

  Environment.ENV = Environment.TEST

  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@")
    println("CONTROLLER TESTS")
    println("@@@@@@@@@@@@@@@@\n")

    /* We are testing HTTP server output doing its own thing in a different process, so we cannot
       and should not write to anything - the connection here is just to explore the state of the DB.
       The DB is shared - the connection is not.
    */

    RequestContext.conn.value = Some(testApp.txManager.connection(readOnly = true))
  }

  override def afterAll(): Unit = {
    testApp.txManager.close()
  }

}
