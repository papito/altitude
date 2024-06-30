package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.test.core.IntegrationTestCore

object PostgresBundleSetup {

  def setup(): Unit = {
    IntegrationTestCore.createTestDir(PostgresSuiteBundle.app)

    val conn = PostgresSuiteBundle.app.txManager.connection(readOnly = false)
    val stmt = conn.createStatement()

    try {
      stmt.executeUpdate("DROP SCHEMA IF EXISTS \"public\" CASCADE; CREATE SCHEMA \"public\";")
      conn.commit()
    }
    finally {
      stmt.close()
      conn.close()
    }

    PostgresSuiteBundle.app.service.migrationService.migrate()
  }
}

trait PostgresBundleSetup extends Suite with BeforeAndAfterAll {
  Environment.ENV = Environment.TEST
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@@@")
    println("POSTGRES INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@@@\n")

    PostgresBundleSetup.setup()

    // See: https://github.com/papito/altitude/wiki/How-the-tests-work#why-do-tests-create-a-top-level-database-connection
    RequestContext.conn.value = Some(PostgresSuiteBundle.app.txManager.connection(readOnly = false))
  }

  override def afterAll(): Unit = {
    PostgresSuiteBundle.app.txManager.close()
  }
}
