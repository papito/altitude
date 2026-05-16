package altitude.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import java.io.File
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.dao.jdbc.PersonDao
import altitude.core.dao.jdbc.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.service.AssetService
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService
import altitude.core.service.FolderService
import altitude.core.service.ImportPipelineService
import altitude.core.service.LibraryService
import altitude.core.service.MetadataExtractionService
import altitude.core.service.MigrationService
import altitude.core.service.PasetoService
import altitude.core.service.PersonService
import altitude.core.service.PurgePipelineService
import altitude.core.service.RepositoryService
import altitude.core.service.SearchService
import altitude.core.service.StatsService
import altitude.core.service.SystemService
import altitude.core.service.UrlService
import altitude.core.service.UserMetadataService
import altitude.core.service.UserService
import altitude.core.service.filestore.FileStoreService
import altitude.core.service.filestore.FileSystemStoreService
import altitude.core.transactions.TransactionManager

class Altitude(val dbEngineOverride: Option[String] = None):
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
   * In short: FORCE_CONFIG_db_engine=postgres will override db.engine=sqlite in the config. This is only for tests,
   *
   * Default reference configs are in altitude/resources/reference.conf and test/resources/reference.conf
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
            .withValue(Const.Conf.POSTGRES_URL, ConfigValueFactory.fromAnyRef("jdbc:postgresql://localhost:5433/altitude-test"))
            .withValue(Const.Conf.POSTGRES_USER, ConfigValueFactory.fromAnyRef("altitude-test"))
            .withValue(Const.Conf.POSTGRES_PASSWORD, ConfigValueFactory.fromAnyRef("testdba"))

        case None =>
          ConfigFactory
            .systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(Const.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))
            .withValue(Const.Conf.POSTGRES_URL, ConfigValueFactory.fromAnyRef("jdbc:postgresql://localhost:5433/altitude-test"))
            .withValue(Const.Conf.POSTGRES_USER, ConfigValueFactory.fromAnyRef("altitude-test"))
            .withValue(Const.Conf.POSTGRES_PASSWORD, ConfigValueFactory.fromAnyRef("testdba"))
      }

    case _ =>
      throw new RuntimeException("Unknown environment")

  }

  final def dataPath: String =
    val dataDir: String = app.config.getString(Const.Conf.FS_DATA_DIR)
    FilenameUtils.concat(Environment.ROOT_PATH, dataDir)

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

    val user: dao.UserDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.UserDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.UserDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val userToken: dao.UserTokenDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.UserTokenDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.UserTokenDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val repository: dao.RepositoryDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.RepositoryDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.jdbc.RepositoryDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val asset: dao.AssetDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.AssetDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.jdbc.AssetDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val folder: dao.FolderDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.FolderDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.FolderDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val metadataField: dao.UserMetadataFieldDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.MetadataFieldDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.MetadataFieldDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val search: dao.SearchDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.SearchDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.sqlite.SearchDao(app.config)
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val person: dao.PersonDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new PersonDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new PersonDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val face: dao.FaceDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.FaceDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.sqlite.FaceDao(app.config)
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val stats: dao.StatDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.StatDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.StatDao(app.config) with dao.sqlite.SqliteOverrides
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
    val paseto = new PasetoService(app)
    val user = new UserService(app)
    val repository = new RepositoryService(app)
    val metadataExtractor = new MetadataExtractionService
    val metadata = new UserMetadataService(app)
    val library = new LibraryService(app)
    val search = new SearchService(app)
    val asset = new AssetService(app)
    val folder = new FolderService(app)
    val stats = new StatsService(app)
    val person = new PersonService(app)
    val faceDetection = new FaceDetectionService(app)
    val faceRecognition = new FaceRecognitionService(app)
    val importPipeline = new ImportPipelineService(app)
    val purgePipeline = new PurgePipelineService(app)
    val urlService = new UrlService()

    val fileStore: FileStoreService = fileStoreType match {
      case Const.StorageEngineName.FS => new FileSystemStoreService(app)
      // S3-based wants to play as well
      case _ => throw new NotImplementedError
    }
  }

  if dataSourceType == Const.DbEngineName.SQLITE then {
    val dbFolder = new File(dataPath, "db")
    if !dbFolder.exists() then {
      logger.info("Creating the DB folder for SQLite: " + dbFolder)
      FileUtils.forceMkdir(dbFolder)
    }
  }

  val parallelism: Int = dataSourceType match {
    case Const.DbEngineName.SQLITE =>
      1 // SQLite doesn't handle concurrent writes well, so we run the pipeline with a parallelism of 1 for SQLite
    case _ => Runtime.getRuntime.availableProcessors() // For other data sources, we can run with max parallelism
  }

  def setIsInitializedState(): Unit =
    this.isInitialized = service.system.readMetadata.isInitialized
    if !this.isInitialized then logger.warn("Instance NOT YET INITIALIZED!")

  def runMigrations(): Unit =
    if Environment.CURRENT == Environment.Name.TEST then return

    if service.migrationService.migrationRequired then
      logger.warn("Migration is required!")
      service.migrationService.migrate()

  def cleanup(): Unit =
    logger.info("Cleaning up resources")
    service.importPipeline.shutdown()
    logger.info("Pipeline system terminated")

    // This is already done by default and will cause a warning
    // actorSystem.terminate()

  // id -> repository
  var repositoriesById: Map[String, Repository] = Map[String, Repository]()

  def clearState(): Unit =
    repositoriesById = Map.empty

  logger.info("Altitude Server instance initialized")
