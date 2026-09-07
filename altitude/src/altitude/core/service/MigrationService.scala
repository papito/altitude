package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.io.Source

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.transactions.TransactionManager

abstract class MigrationService(val app: Altitude):
  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  protected val CURRENT_VERSION: Int
  protected val MIGRATIONS_DIR: String

  /** Code that must run once a version's schema change is committed. No version needs any so far. */
  private def migrateVersion(version: Int): Unit = ()

  private def executeCommand(command: String): Unit =
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

  private def runSqlScript(path: String): Unit =
    logger.info(s"Running migration script: $path")
    val resourceUrl = getClass.getResource(path)
    val source = Source.fromURL(resourceUrl)
    val commands = source.mkString
    source.close()

    txManager.withTransaction {
      executeCommand(commands)
    }

  def migrationRequired: Boolean =
    logger.info("Checking if migration is required")
    val version = app.service.system.version
    logger.info(s"Current database version is @ $version")
    val isRequired = version < CURRENT_VERSION
    logger.info(s"Migration required? : $isRequired")
    isRequired

  /**
   * A fresh database (version 0) gets the whole current schema from `all.sql` in one step and is stamped with the current
   * version. An existing database is brought forward one version at a time with `<version>.sql`, in every environment, so a
   * development database keeps its data across schema changes just as a production one does.
   */
  def migrate(): Unit =
    val oldVersion = app.service.system.version
    logger.warn("!!!! MIGRATING !!!!")
    logger.info(s"From version $oldVersion to $CURRENT_VERSION")

    if oldVersion == 0 then
      runSqlScript(s"$MIGRATIONS_DIR/all.sql")
      txManager.withTransaction {
        app.service.system.setVersion(CURRENT_VERSION)
      }
    else
      for version <- oldVersion + 1 to CURRENT_VERSION do
        runSqlScript(s"$MIGRATIONS_DIR/$version.sql")

        // must have schema changes committed
        txManager.withTransaction {
          migrateVersion(version)
          app.service.system.setVersion(version)
        }
