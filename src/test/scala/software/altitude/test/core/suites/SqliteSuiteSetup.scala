package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Configuration
import software.altitude.core.Environment
import software.altitude.test.core.integration.IntegrationTestCore

import java.sql.DriverManager

trait SqliteSuiteSetup extends Suite with BeforeAndAfterAll {
  Environment.ENV = Environment.TEST
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    IntegrationTestCore.createTestDir(SqliteSuite.app)

    log.info("TEST. Resetting DB schema once")
    val url: String = new Configuration().getString("db.sqlite.url")
    DriverManager.registerDriver(new org.sqlite.JDBC)

    log.info("Clearing sqlite database")
    val sql =
      """
        PRAGMA writable_schema = 1;
        delete from sqlite_master where type in ('table', 'index', 'trigger');
        PRAGMA writable_schema = 0;
        VACUUM;
        PRAGMA INTEGRITY_CHECK;
      """.stripMargin

    val conn = DriverManager.getConnection(url)
    val stmt = conn.createStatement()

    try {
      stmt.executeUpdate(sql)
    }
    finally {
      if (stmt != null) stmt.close()
      if (conn != null) conn.close()
    }

    SqliteSuite.app.service.migrationService.migrate()
    log.info("END SETUP")
  }
}
