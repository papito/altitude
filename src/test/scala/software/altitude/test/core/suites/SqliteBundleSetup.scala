package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.test.core.IntegrationTestCore

trait SqliteBundleSetup extends Suite with BeforeAndAfterAll {
  Environment.ENV = Environment.TEST
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@")
    println("SQLITE INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@\n")

    IntegrationTestCore.createTestDir(SqliteSuiteBundle.app)

    log.info("Clearing Sqlite database")
    val sql =
      """
        PRAGMA writable_schema = 1;
        delete from sqlite_master where type in ('table', 'index', 'trigger');
        PRAGMA writable_schema = 0;
        VACUUM;
        PRAGMA INTEGRITY_CHECK;
      """.stripMargin

    val conn = SqliteSuiteBundle.app.txManager.connection(readOnly = false)
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

    SqliteSuiteBundle.app.service.migrationService.migrate()

    // See: https://github.com/papito/altitude/wiki/How-the-tests-work#why-do-tests-create-a-top-level-database-connection
    RequestContext.conn.value = Some(SqliteSuiteBundle.app.txManager.connection(readOnly = false))
  }

  override def afterAll(): Unit = {
    SqliteSuiteBundle.app.txManager.close()
  }
}
