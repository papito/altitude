package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import software.altitude.core.Altitude
import software.altitude.core.{Const => C}
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.testAltitudeApp

object SqliteSuiteBundle {
  val testApp: Altitude = new Altitude(dbEngineOverride = Some(C.DbEngineName.SQLITE))
}

class SqliteSuiteBundle
  extends AllIntegrationTestSuites(testApp = SqliteSuiteBundle.testApp)
    with testAltitudeApp with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@")
    println("SQLITE INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@\n")

    IntegrationTestUtil.createTestDir(testApp)

    val sql =
      """
        PRAGMA writable_schema = 1;
        delete from sqlite_master where type in ('table', 'index', 'trigger');
        PRAGMA writable_schema = 0;
        VACUUM;
        PRAGMA INTEGRITY_CHECK;
      """.stripMargin

    val conn = testApp.txManager.connection(readOnly = false)
    // disables transaction for this connection (cannot user VACUUM in a transaction)
    conn.setAutoCommit(true)

    val stmt = conn.createStatement()

    try {
      stmt.executeUpdate(sql)
    }
    finally {
      stmt.close()
      conn.close()
    }

    testApp.service.migrationService.migrate()

  }

  override def afterAll(): Unit = {
    testApp.cleanup()
  }
}
