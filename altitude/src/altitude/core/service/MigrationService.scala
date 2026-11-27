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
 * the database through the system metadata DAO and `txManager` alone: the instance's, or a test's of its own.
 */
class MigrationService(app: Altitude, txManager: TransactionManager, currentVersion: Int, migrationsDir: String):
  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val systemMetadataDao: SystemMetadataDao = app.DAO.systemMetadata

  /** Code a version needs once its script has run, in the script's transaction. No version needs any so far. */
  private def migrateVersion(version: Int): Unit = ()

  private def executeCommand(command: String): Unit =
    val stmt = RequestContext.getConn.createStatement()

    // `executeUpdate` fails on a Postgres script whose statements return values (nextval); `execute` fails on SQLite
    try
      app.dataSourceType match {
        case Const.DbEngineName.SQLITE =>
          stmt.executeUpdate(command)
        case Const.DbEngineName.POSTGRES =>
          stmt.execute(command)
      }
    finally stmt.close()

  /** The script at `path`; a missing one fails naming it and the versions */
  private def script(path: String, fromVersion: Int): String =
    logger.debug(s"Reading migration script: $path")
    val resourceUrl = Option(getClass.getResource(path)).getOrElse {
      throw IllegalStateException(
        s"No migration script $path: the database is at schema version $fromVersion, this build at $currentVersion, and nothing " +
          "else brings it forward")
    }
    val source = Source.fromURL(resourceUrl)
    try source.mkString
    finally source.close()

  /** The first column of each row `sql` answers, a NULL as None */
  private def firstColumn(sql: String): List[Option[String]] =
    val statement = RequestContext.getConn.createStatement()
    try
      val rows = statement.executeQuery(sql)
      Iterator.continually(rows).takeWhile(_.next()).map(rows => Option(rows.getString(1))).toList
    finally statement.close()

  /**
   * Refuses, before `all.sql` runs, a database whose `system` table the search found nowhere but which is no empty database, so
   * whatever it holds stays as it is: `all.sql` drops nothing, and would otherwise create the schema beside those tables or fail
   * part way. Refused: a schema to create into that holds a table (another application's database, or an Altitude one whose
   * `system` table is gone), and on PostgreSQL a search path that names no schema that exists, or that leads past a schema
   * holding a `system` table (a role whose search path changed since the first start).
   */
  private def requireEmpty(): Unit =
    if app.dataSourceType == Const.DbEngineName.POSTGRES then requireSearchPathToSchema()

    // On PostgreSQL the current schema, the first of the search path that exists, not counting an extension's own relations
    // (`pg_stat_statements`' views, PostGIS' table); on SQLite neither SQLite's own tables nor sqlite-vector's, which it creates
    // in every database it is loaded into. One more than is shown, to know whether there are more.
    val tables = firstColumn(app.dataSourceType match
      case Const.DbEngineName.POSTGRES =>
        """SELECT n.nspname || '.' || c.relname
             FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
              AND NOT EXISTS (
                SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
            ORDER BY 1 LIMIT 6"""
      case _ =>
        """SELECT name FROM sqlite_master
            WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\_%' ESCAPE '\' AND name <> '_sqliteai_vector'
            ORDER BY 1 LIMIT 6""").flatten

    if tables.nonEmpty then
      val named = tables.take(5).mkString(", ") + (if tables.size > 5 then ", …" else "")
      throw IllegalStateException(
        s"The database holds tables ($named) but no Altitude schema, which is created only in an empty database: point the " +
          "database URL at an empty database or at Altitude's own")

  /** PostgreSQL: the search path leads to a schema to create into, and to the one that holds a `system` table if any does */
  private def requireSearchPathToSchema(): Unit =
    val searchPath = firstColumn("SELECT current_setting('search_path')").flatten.mkString
    if firstColumn("SELECT current_schema()").flatten.isEmpty then
      throw IllegalStateException(
        s"No schema of the search path ($searchPath) exists, so there is none to create Altitude's schema in: set the role's " +
          "search_path to a schema that exists, such as public")

    val elsewhere = firstColumn("""SELECT n.nspname
           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE c.relname = 'system' AND c.relkind IN ('r', 'p')
            AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname <> current_schema()
          ORDER BY 1""").flatten
    if elsewhere.nonEmpty then
      throw IllegalStateException(
        s"Altitude's system table is in the schema ${elsewhere.mkString(", ")}, which the search path ($searchPath) does not " +
          "lead to: set the role's search_path to lead to it, or point the database URL at an empty database")

  /**
   * The stored schema version; 0 for a database with no schema yet, whose `system` table does not exist. Any other failure to
   * read it is rethrown: taken for a fresh database, an existing one would have `all.sql` run over it.
   */
  private def version: Int =
    txManager.asReadOnly {
      try systemMetadataDao.getById(SystemMetadataDao.SYSTEM_RECORD_ID.toString).version
      catch case ex: SQLException if MigrationService.isMissingTable(ex) => 0
    }

  /**
   * A fresh database (version 0) gets the whole schema from `all.sql`, stamped with the current version (the script seeds 0), and
   * fresh connections in place of those opened before its schema existed. An existing one is brought forward a version at a time
   * with `<version>.sql`, every script read before the first runs. A script runs in one transaction with its version's code and
   * the stamp of its version (DDL is transactional on both engines), so a failure or a stop leaves the version before it, never a
   * schema the stored version does not describe.
   */
  def migrate(): Unit =
    val oldVersion = version
    logger.debug(s"Database schema version $oldVersion, current $currentVersion")
    if oldVersion >= currentVersion then return

    if oldVersion == 0 then
      logger.info("Creating the schema of a fresh database")
      val schema = script(s"$migrationsDir/all.sql", oldVersion)
      txManager.withTransaction {
        requireEmpty()
        executeCommand(schema)
        systemMetadataDao.updateVersion(toVersion = currentVersion)
      }
      txManager.schemaCreated()
    else
      logger.warn(s"Migrating the database from version $oldVersion to $currentVersion")
      val scripts =
        (oldVersion + 1 to currentVersion).map(version => version -> script(s"$migrationsDir/$version.sql", oldVersion))
      for (version, change) <- scripts do
        txManager.withTransaction {
          executeCommand(change)
          migrateVersion(version)
          systemMetadataDao.updateVersion(toVersion = version)
        }

object MigrationService:
  // PostgreSQL's undefined_table; sqlite-jdbc reports a missing table as a plain SQLITE_ERROR with this message
  private def isMissingTable(ex: SQLException): Boolean =
    ex.getSQLState == "42P01" || Option(ex.getMessage).exists(_.contains("no such table"))
