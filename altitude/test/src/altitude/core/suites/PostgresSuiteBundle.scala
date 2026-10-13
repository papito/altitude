package altitude.core.suites

import altitude.test.TestAltitudeApp
import org.scalatest.BeforeAndAfterAll

import altitude.core.Altitude
import altitude.core.Const as C

object PostgresSuiteBundle {
  val testApp: Altitude = new Altitude(dbEngineOverride = Some(C.DbEngineName.POSTGRES))

  def setup(testApp: Altitude): Unit = TestDatabase.reset(testApp)
}

class PostgresSuiteBundle
  extends AllIntegrationTestSuites(testApp = PostgresSuiteBundle.testApp)
  with TestAltitudeApp
  with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@@@")
    println("POSTGRES INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@@@\n")

    PostgresSuiteBundle.setup(this.testApp)
  }

  override def afterAll(): Unit = {
    testApp.cleanup()
  }
}
