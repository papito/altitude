package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import software.altitude.core.Altitude
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.core.{Const => C}
import software.altitude.test.core.testAltitudeApp

object PostgresSuiteBundle {
  Environment.ENV = Environment.TEST
  val testApp: Altitude = new Altitude(configOverride = Map("datasource" -> C.DatasourceType.POSTGRES))

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

  Environment.ENV = Environment.TEST

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@@@")
    println("POSTGRES INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@@@\n")

    PostgresSuiteBundle.setup(this.testApp)

    // See: https://github.com/papito/altitude/wiki/How-the-tests-work#why-do-tests-create-a-top-level-database-connection
    RequestContext.conn.value = Some(testApp.txManager.connection(readOnly = false))
  }

  override def afterAll(): Unit = {
    testApp.txManager.close()
  }
}
