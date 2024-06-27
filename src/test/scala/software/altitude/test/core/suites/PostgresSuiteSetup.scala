package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.test.core.IntegrationTestCore

object PostgresSuiteSetup {

  def setup(): Unit = {
    IntegrationTestCore.createTestDir(PostgresSuite.app)

    val conn = PostgresSuite.app.txManager.connection(readOnly = false)
    val stmt = conn.createStatement()

    try {
      stmt.executeUpdate("DROP SCHEMA IF EXISTS \"public\" CASCADE; CREATE SCHEMA \"public\";")
      conn.commit()
    }
    finally {
      stmt.close()
      conn.close()
    }

    PostgresSuite.app.service.migrationService.migrate()
  }
}

trait PostgresSuiteSetup extends Suite with BeforeAndAfterAll {
  Environment.ENV = Environment.TEST
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@@@@@@@@@@@@@@@@@")
    println("POSTGRES INTEGRATION TESTS")
    println("@@@@@@@@@@@@@@@@@@@@@@@@@@\n")

    PostgresSuiteSetup.setup()

    RequestContext.conn.value = Some(PostgresSuite.app.txManager.connection(readOnly = false))
  }

  override def afterAll(): Unit = {
    PostgresSuite.app.txManager.close()
  }
}
