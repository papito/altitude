package software.altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.io.Source

import software.altitude.core.Altitude
import software.altitude.core.Const
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.core.transactions.TransactionManager

abstract class MigrationService(val app: Altitude) {
  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  protected val CURRENT_VERSION: Int

  private def migrateVersion(version: Int): Unit = {
    version match {
      case 1 => v1()
    }
  }

  private def v1(): Unit = {}

  protected val MIGRATIONS_DIR: String

  private def executeCommand(command: String): Unit = {
    val stmt = RequestContext.getConn.createStatement()

    /**
     * Postgres next_val returns values, confusing `executeUpdate`. Yet, using `execute` for both blows up Sqlite dev server
     * ¯\_(ツ)_/¯ I don't got time for this.
     */
    app.dataSourceType match {
      case Const.DbEngineName.SQLITE =>
        stmt.executeUpdate(command)
      case Const.DbEngineName.POSTGRES =>
        stmt.execute(command)
    }

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
    logger.info("Checking if migration is required")
    val version = app.service.system.version
    logger.info(s"Current database version is @ $version")
    val isRequired = version < CURRENT_VERSION
    logger.info(s"Migration required? : $isRequired")
    isRequired
  }

  def migrate(): Unit = {
    val oldVersion = app.service.system.version
    logger.warn("!!!! MIGRATING !!!!")
    logger.info(s"From version $oldVersion to $CURRENT_VERSION")
    for (version <- oldVersion + 1 to CURRENT_VERSION) {
      runMigration(version)
    }
  }

  private def parseMigrationCommands(version: Int): String = {
    logger.info(s"RUNNING MIGRATION TO VERSION ^^$version^^")

    val entireSchemaPath = s"$MIGRATIONS_DIR/all.sql"

    /**
     * We load the entire schema as the one and only migration in the following cases:
     *   1. In test and dev environments 2. When initiating version 1 in prod
     */
    val path = Environment.CURRENT match {
      case Environment.Name.TEST | Environment.Name.DEV => entireSchemaPath
      case Environment.Name.PROD =>
        if (version == 1) entireSchemaPath
        else
          s"$MIGRATIONS_DIR/$version.sql"
    }

    logger.info(s"Migration path: $path")
    val resourceUrl = getClass.getResource(path)
    val source = Source.fromURL(resourceUrl)

    val commands = source.mkString
    source.close()

    commands
  }
}
