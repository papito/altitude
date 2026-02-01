package altitude.core

import altitude.core.dao.jdbc.SystemMetadataDao
import altitude.core.service.{MigrationService, SystemService}
import altitude.core.transactions.TransactionManager
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory

import java.io.File
import org.apache.commons.io.FilenameUtils
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Altitude(val dbEngineOverride: Option[String] = None) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Environment is: ${Environment.CURRENT}")

  final val app: Altitude = this

  // ID for this application
  final val id: Int = scala.util.Random.nextInt(java.lang.Integer.MAX_VALUE)
  logger.info(s"Initializing Altitude Server application. Instance ID [$id]")

  /**
   * In development, application-dev.conf will override system defaults.
   *
   * Production JAR defaults to SQLITE and can be overridden by application.conf.
   *
   * ENV var overrides are a Typesafe Config feature:
   * https://github.com/lightbend/config?tab=readme-ov-file#optional-system-or-env-variable-overrides
   *
   * In short: FORCE_CONFIG_db_engine=mongo will override db.engine=mysql in the config. This is only for tests,
   *
   * Default reference configs are in src/main/resources/reference.conf and src/test/resources/reference.conf
   *
   * For DEV and PROD, application*.conf files have the final say - and are in the root of the project (and along the live JAR in
   * release)
   */

  // the config before final actual config as we need to dynamically figure out some values
  private val preConfig: Config = Environment.CURRENT match {
    case Environment.Name.DEV =>
      ConfigFactory
        .parseFile(new File("application-dev.conf"))
        .withFallback(ConfigFactory.defaultReference())

    case Environment.Name.PROD =>
      ConfigFactory
        .parseFile(new File("application.conf"))
        .withFallback(ConfigFactory.defaultReference())

    case Environment.Name.TEST =>
      val relativeTestDir = ConfigFactory.defaultReference().getString(Const.Conf.TEST_DIR)
      val fsDataDirName = ConfigFactory.defaultReference().getString(Const.Conf.FS_DATA_DIR)
      val relativeFsDataDir = FilenameUtils.concat(relativeTestDir, fsDataDirName)

      dbEngineOverride match {
        case Some(ds) =>
          ConfigFactory
            .systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(Const.Conf.DB_ENGINE, ConfigValueFactory.fromAnyRef(ds))
            .withValue(Const.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))

        case None =>
          ConfigFactory
            .systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(Const.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))
      }

    case _ =>
      throw new RuntimeException("Unknown environment")

  }

  final def dataPath: String = {
    val dataDir: String = app.config.getString(Const.Conf.FS_DATA_DIR)
    FilenameUtils.concat(Environment.ROOT_PATH, dataDir)
  }

  /** Heroically assemble SQLITE URL based on what we have */
  final private val sqliteRelDbPath = preConfig.getString(Const.Conf.REL_SQLITE_DB_PATH)

  final val config: Config = Environment.CURRENT match {

    case Environment.Name.TEST =>
      val testDir = preConfig.getString(Const.Conf.TEST_DIR)
      val sqliteTestUrl = s"jdbc:sqlite:$testDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteTestUrl))
    case _ =>
      val dataDir = preConfig.getString(Const.Conf.FS_DATA_DIR)
      val sqliteUrl = s"jdbc:sqlite:$dataDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteUrl))
  }

  /**
   * Has the first admin user been created? This flag is loaded from the system metadata table upon start and then cached for the
   * lifetime of the application instance.
   *
   * This is to avoid getting the value from the database every time we need it.
   *
   * See: setIsInitializedState() in this file.
   */
  var isInitialized = false

  final private val schemaVersion = 1

  final val dataSourceType: String = config.getString(Const.Conf.DB_ENGINE)
  logger.info(s"Datasource type: $dataSourceType")

  final val fileStoreType: String = config.getString(Const.Conf.DEFAULT_STORAGE_ENGINE)
  logger.info(s"File store type: $fileStoreType")

  final val txManager: TransactionManager = new altitude.core.transactions.TransactionManager(app.config)

  val actorSystem: ActorSystem[AltitudeActorSystem.Command] =
    ActorSystem[AltitudeActorSystem.Command](AltitudeActorSystem(), "altitude-actor-system")

  object DAO {
    val systemMetadata: SystemMetadataDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.SystemMetadataDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.SystemMetadataDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }
  }

  object service {
    val migrationService: MigrationService = dataSourceType match {
      case Const.DbEngineName.SQLITE =>
        new MigrationService(app) {
          final override val CURRENT_VERSION = schemaVersion
          final override val MIGRATIONS_DIR = "/migrations/sqlite"
        }
      case Const.DbEngineName.POSTGRES =>
        new MigrationService(app) {
          final override val CURRENT_VERSION = schemaVersion
          final override val MIGRATIONS_DIR = "/migrations/postgres"
        }
    }

    val system = new SystemService(app)
  }

  def setIsInitializedState(): Unit = {
    this.isInitialized = service.system.readMetadata.isInitialized
    if (!this.isInitialized) {
      logger.warn("Instance NOT YET INITIALIZED!")
    }
  }

  def runMigrations(): Unit = {
    if (Environment.CURRENT == Environment.Name.TEST) {
      return
    }

    if (service.migrationService.migrationRequired) {
      logger.warn("Migration is required!")
      service.migrationService.migrate()
    }
  }

  def cleanup(): Unit = {
    logger.info("Cleaning up resources")
    // service.importPipeline.shutdown()
    logger.info("Pipeline system terminated")

    // This is already done by default and will cause a warning
    // actorSystem.terminate()
  }


  runMigrations()

  logger.info("Altitude Server instance initialized")

}
