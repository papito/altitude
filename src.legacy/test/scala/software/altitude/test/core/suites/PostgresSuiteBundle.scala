package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import software.altitude.core.Altitude
import software.altitude.core.{Const => C}
import software.altitude.test.core.testAltitudeApp

object PostgresSuiteBundle {
  val testApp: Altitude = new Altitude(dbEngineOverride = Some(C.DbEngineName.POSTGRES))

  def setup(testApp: Altitude): Unit = {
    val conn = testApp.txManager.connection(readOnly = false)
    val stmt = conn.createStatement()

    try {
      stmt.executeUpdate("DROP SCHEMA IF EXISTS \"public\" CASCADE; CREATE SCHEMA \"public\";")
      conn.commit()
    }
    finally {
      stmt.close()
      conn.close()
    }

    testApp.service.migrationService.migrate()
  }
}

class PostgresSuiteBundle
  extends AllIntegrationTestSuites(testApp = PostgresSuiteBundle.testApp)
    with testAltitudeApp with BeforeAndAfterAll {

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
