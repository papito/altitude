package altitude.core.service

import java.sql.SQLException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.io.Source

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.SystemMetadataDao
import altitude.core.transactions.TransactionManager

/**
 * Brings the schema to `currentVersion` with the scripts under `migrationsDir`. It runs before the services exist, so it reaches
 * the database through the system metadata DAO and the transaction manager alone.
 */
class MigrationService(app: Altitude, currentVersion: Int, migrationsDir: String):
  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val txManager: TransactionManager = app.txManager
  private val systemMetadataDao: SystemMetadataDao = app.DAO.systemMetadata

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
    logger.debug(s"Running migration script: $path")
    val resourceUrl = getClass.getResource(path)
    val source = Source.fromURL(resourceUrl)
    val commands = source.mkString
    source.close()

    txManager.withTransaction {
      executeCommand(commands)
    }

  /**
   * The stored schema version; 0 for a database with no schema yet, whose `system` table does not exist. Any other failure to
   * read it is rethrown: taken for a fresh database, an existing one would have `all.sql` run over it.
   */
  private def version: Int =
    txManager.asReadOnly {
      try systemMetadataDao.getById(SystemMetadataDao.SYSTEM_RECORD_ID.toString).version
      catch case ex: SQLException if MigrationService.isMissingTable(ex) => 0
    }

  private def setVersion(version: Int): Unit =
    txManager.withTransaction {
      systemMetadataDao.updateVersion(toVersion = version)
    }

  /**
   * A fresh database (version 0) gets the whole current schema from `all.sql` in one step and is stamped with the current
   * version. An existing database is brought forward one version at a time with `<version>.sql`, in every environment, so a
   * development database keeps its data across schema changes just as a production one does. A database at the current version is
   * left alone.
   */
  def migrate(): Unit =
    val oldVersion = version
    logger.debug(s"Database schema version $oldVersion, current $currentVersion")
    if oldVersion >= currentVersion then return

    if oldVersion == 0 then
      logger.info("Creating the schema of a fresh database")
      runSqlScript(s"$migrationsDir/all.sql")
      setVersion(currentVersion)
    else
      logger.warn(s"Migrating the database from version $oldVersion to $currentVersion")
      for version <- oldVersion + 1 to currentVersion do
        runSqlScript(s"$migrationsDir/$version.sql")

        // must have schema changes committed
        txManager.withTransaction {
          migrateVersion(version)
          setVersion(version)
        }

object MigrationService:
  // PostgreSQL's undefined_table; sqlite-jdbc reports a missing table as a plain SQLITE_ERROR with this message
  private def isMissingTable(ex: SQLException): Boolean =
    ex.getSQLState == "42P01" || Option(ex.getMessage).exists(_.contains("no such table"))
