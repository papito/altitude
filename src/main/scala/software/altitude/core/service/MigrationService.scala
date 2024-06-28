package software.altitude.core.service
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.core.transactions.TransactionManager

import java.io.File
import scala.io.Source

abstract class MigrationService(val app: Altitude)  {
  protected val log: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  protected val CURRENT_VERSION: Int

  private def migrateVersion(version: Int): Unit = {
      version match {
        case 1 => v1()
      }
  }

  private def v1(): Unit = {
  }

  protected val MIGRATIONS_DIR: String
  private val FILE_EXTENSION = ".sql"

  /**
   * Execute an arbitrary command
   */
  private def executeCommand(command: String): Unit = {
    val stmt = RequestContext.getConn.createStatement()
    stmt.executeUpdate(command)
    stmt.close()
  }

  private def runMigration(version: Int): Unit = {
    val sqlCommands = parseMigrationCommands(version)

    txManager.withTransaction {
      executeCommand(sqlCommands)
    }

    // must have schema changes committed
    txManager.withTransaction {
      migrateVersion(version)
      app.service.system.versionUp()
    }
  }

  def migrationRequired: Boolean = {
    log.info("Checking if migration is required")
    val version = app.service.system.version
    log.info(s"Current database version is @ $version")
    val isRequired = version < CURRENT_VERSION
    log.info(s"Migration required? : $isRequired")
    isRequired
  }

  def migrate(): Unit = {
    val oldVersion = app.service.system.version
    log.warn("!!!! MIGRATING !!!!")
    log.info(s"From version $oldVersion to $CURRENT_VERSION")
    for (version <- oldVersion + 1 to CURRENT_VERSION) {
      runMigration(version)
    }
  }

  private def parseMigrationCommands(version: Int): String = {
    log.info(s"RUNNING MIGRATION TO VERSION ^^$version^^")

    val entireSchemaPath = new File(MIGRATIONS_DIR, "all.sql").toString

    /* We load the entire schema as the one and only migration in the following cases:
        * 1. In test and dev environments
        * 2. When migrating to version 1 in prod
     */
    val path = Environment.ENV match {
      case Environment.TEST | Environment.DEV => entireSchemaPath
      case Environment.PROD => if (version == 1) entireSchemaPath
      else
        new File(MIGRATIONS_DIR, s"$version$FILE_EXTENSION").toString
    }

    log.info(s"Migration path: $path")

    val r = getClass.getResource(path)
    val source = Source.fromURL(r)
    val commands = source.mkString
    source.close()

    commands
  }
}
