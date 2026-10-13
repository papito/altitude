package altitude.core.suites

import altitude.test.TestAltitudeApp
import org.scalatest.BeforeAndAfterAll

import altitude.core.Altitude
import altitude.core.Const as C

object SqliteSuiteBundle {
  val testApp: Altitude = new Altitude(dbEngineOverride = Some(C.DbEngineName.SQLITE))

  def setup(): Unit = {
    TestDatabase.reset(testApp)

    testApp.txManager.withTransaction {
      testApp.DAO.systemMetadata.setInitialized()
    }

    testApp.isInitialized = true
  }
}

class SqliteSuiteBundle
  extends AllIntegrationTestSuites(testApp = SqliteSuiteBundle.testApp)
  with TestAltitudeApp
  with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@")
    println("SQLITE INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@\n")

    SqliteSuiteBundle.setup()
  }

  override def afterAll(): Unit = {
    testApp.cleanup()
  }
}
