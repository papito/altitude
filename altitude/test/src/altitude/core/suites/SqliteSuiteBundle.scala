package altitude.core.suites

import org.scalatest.BeforeAndAfterAll
import altitude.core.Altitude
import altitude.core.Const as C
import altitude.test.{IntegrationTestUtil, TestAltitudeApp}

object SqliteSuiteBundle {
  val testApp: Altitude = new Altitude(dbEngineOverride = Some(C.DbEngineName.SQLITE))

  def setup(): Unit = {
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
}

class SqliteSuiteBundle
  extends AllIntegrationTestSuites(testApp = SqliteSuiteBundle.testApp)
    with TestAltitudeApp with BeforeAndAfterAll {

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
