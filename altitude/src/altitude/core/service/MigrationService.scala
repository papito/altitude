package altitude.core.service

import java.util.TimeZone
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.io.Source

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.transactions.TransactionManager

object MigrationService:
  /**
   * The JVM zone ID as PostgreSQL spells it, mirroring pgJDBC's startup conversion: IANA IDs pass through, while a fixed-offset
   * "GMT+hh:mm" flips its sign because PostgreSQL reads such offsets the POSIX way.
   */
  def postgresTimeZone(jvmZoneId: String): String =
    if jvmZoneId.length <= 3 || !jvmZoneId.startsWith("GMT") then jvmZoneId
    else
      jvmZoneId.charAt(3) match
        case '+' => "GMT-" + jvmZoneId.substring(4)
        case '-' => "GMT+" + jvmZoneId.substring(4)
        case _ => jvmZoneId

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
      pinSessionTimeZoneToJvm()
      executeCommand(commands)
    }

  /**
   * Conversions between timestamp types in a PostgreSQL migration must use the zone the application decoded the values with: the
   * JVM default zone, which pgJDBC also sends at connection start. Pin it explicitly (SET LOCAL semantics) so neither the server
   * default nor a URL override can change what a migration script's `current_setting('TimeZone')` means.
   */
  private def pinSessionTimeZoneToJvm(): Unit =
    app.dataSourceType match
      case Const.DbEngineName.POSTGRES =>
        val zone = MigrationService.postgresTimeZone(TimeZone.getDefault.getID)
        logger.info(s"Migration session time zone pinned to the JVM zone [$zone]")
        val stmt = RequestContext.getConn.prepareStatement("SELECT set_config('TimeZone', ?, true)")
        stmt.setString(1, zone)
        stmt.execute()
        stmt.close()
      case _ => ()

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
