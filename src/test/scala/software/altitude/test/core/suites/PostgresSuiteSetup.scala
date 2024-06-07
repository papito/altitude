package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Configuration
import software.altitude.core.Environment
import software.altitude.test.core.integration.IntegrationTestCore

import java.sql.DriverManager
import java.util.Properties

trait PostgresSuiteSetup extends Suite with BeforeAndAfterAll {
  Environment.ENV = Environment.TEST
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    IntegrationTestCore.createTestDir(PostgresSuite.app)

    log.info("TEST. Resetting DB schema once")
    DriverManager.registerDriver(new org.postgresql.Driver)
    val appConfig = new Configuration()
    val props = new Properties
    val user = appConfig.getString("db.postgres.user")
    props.setProperty("user", user)
    val password = appConfig.getString("db.postgres.password")
    props.setProperty("password", password)
    val url = appConfig.getString("db.postgres.url")

    log.info("Clearing postgres database")
    val sql = "DROP SCHEMA IF EXISTS \"altitude-test\" CASCADE; CREATE SCHEMA \"altitude-test\";"

    val conn = DriverManager.getConnection(url, props)
    val stmt = conn.createStatement()
    try {
      stmt.executeUpdate(sql)
    }
    finally {
      if (stmt != null) stmt.close()
      if (conn != null) conn.close()
    }

    PostgresSuite.app.service.migrationService.migrate()
    log.info("END SETUP")
  }
}
