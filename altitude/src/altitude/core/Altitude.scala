package altitude.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import java.io.File
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

import altitude.core.dao.jdbc.PersonDao
import altitude.core.dao.jdbc.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.service.AlbumService
import altitude.core.service.AssetService
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService
import altitude.core.service.FolderService
import altitude.core.service.GeocoderService
import altitude.core.service.ImportPipelineService
import altitude.core.service.LibraryService
import altitude.core.service.LocationService
import altitude.core.service.MetadataExtractionService
import altitude.core.service.MigrationService
import altitude.core.service.PasetoService
import altitude.core.service.PersonService
import altitude.core.service.PurgePipelineService
import altitude.core.service.RepositoryService
import altitude.core.service.SearchService
import altitude.core.service.StagingService
import altitude.core.service.StatsService
import altitude.core.service.SystemService
import altitude.core.service.UrlService
import altitude.core.service.UserMetadataService
import altitude.core.service.UserService
import altitude.core.service.VideoService
import altitude.core.service.filestore.FileStoreService
import altitude.core.service.filestore.FileSystemStoreService
import altitude.core.transactions.SqlExplainer
import altitude.core.transactions.TransactionManager

class Altitude(val dbEngineOverride: Option[String] = None):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.debug(s"Environment is: ${Environment.CURRENT}")

  final val app: Altitude = this

  // ID for this application
  final val id: Int = scala.util.Random.nextInt(java.lang.Integer.MAX_VALUE)
  logger.debug(s"Initializing Altitude Server application. Instance ID [$id]")

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

  // Short, so the test of the time limit for reads does not wait long. The main reference.conf comes first on the test
  // classpath, so a key both reference files set keeps the main value; test values are set here instead.
  private val TEST_READ_STATEMENT_TIMEOUT = "2s"

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
            .withValue(Const.Conf.POSTGRES_READ_STATEMENT_TIMEOUT, ConfigValueFactory.fromAnyRef(TEST_READ_STATEMENT_TIMEOUT))

        case None =>
          ConfigFactory
            .systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(Const.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))
            .withValue(Const.Conf.POSTGRES_URL, ConfigValueFactory.fromAnyRef("jdbc:postgresql://localhost:5433/altitude-test"))
            .withValue(Const.Conf.POSTGRES_USER, ConfigValueFactory.fromAnyRef("altitude-test"))
            .withValue(Const.Conf.POSTGRES_PASSWORD, ConfigValueFactory.fromAnyRef("testdba"))
            .withValue(Const.Conf.POSTGRES_READ_STATEMENT_TIMEOUT, ConfigValueFactory.fromAnyRef(TEST_READ_STATEMENT_TIMEOUT))
      }

    case _ =>
      throw RuntimeException("Unknown environment")

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

  final private val schemaVersion = 2

  final val dataSourceType: String = config.getString(Const.Conf.DB_ENGINE)
  logger.debug(s"Datasource type: $dataSourceType")

  final val fileStoreType: String = config.getString(Const.Conf.DEFAULT_STORAGE_ENGINE)
  logger.debug(s"File store type: $fileStoreType")

  // The SQL explain log, for development only
  private val sqlExplainer: Option[SqlExplainer] =
    Option.when(Environment.devSwitch(config, Const.Conf.DEV_SQL_EXPLAIN)) {
      val file = new File(Environment.ROOT_PATH, SqlExplainer.FILE_NAME)
      logger.info(s"The SQL explain log is on: each new query is explained once, into $file")
      new SqlExplainer(dataSourceType, file)
    }

  // For development only: a pipeline queue whose stream fails restarts it after a backoff, rather than staying down. Read before
  // the services, whose queues are started as they are wired up.
  final val isPipelineRestartEnabled: Boolean = Environment.devSwitch(config, Const.Conf.DEV_RESTART_PIPELINE)
  if isPipelineRestartEnabled then logger.info("A pipeline queue whose stream fails restarts it after a backoff")

  final val txManager: TransactionManager = TransactionManager(app.config, sqlExplainer)

  /**
   * How many threads do the import's work (`import.parallelism`, see reference.conf): left unset, half the cores and never fewer
   * than 2. Every one of them holds its own face detection and recognition networks.
   */
  final val importParallelism: Int =
    if config.hasPath(Const.Conf.IMPORT_PARALLELISM) then config.getInt(Const.Conf.IMPORT_PARALLELISM)
    else math.max(2, Runtime.getRuntime.availableProcessors / 2)
  logger.info(s"Import parallelism: $importParallelism threads")

  // The app's config, with the dispatcher the import's work runs on
  val actorSystem: ActorSystem[AltitudeActorSystem.Command] =
    val importDispatcherConfig = ConfigFactory.parseString(s"""
      altitude.import-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor.fixed-pool-size = $importParallelism
      }
    """)
    ActorSystem[AltitudeActorSystem.Command](
      AltitudeActorSystem(),
      "altitude-actor-system",
      importDispatcherConfig.withFallback(config))

  /**
   * Where the import pipeline's stages do their work (`PipelineUtils.guardedAsync`), so the stream actors on the default
   * dispatcher, which the status ticker and the SQLite optimize schedule share, only route
   */
  final val importDispatcher: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup(DispatcherSelector.fromConfig("altitude.import-dispatcher"))

  // SQLite refreshes its planner statistics hourly, on the write connection between transactions, and once more at cleanup
  private val sqliteOptimizing: Option[Cancellable] = Option.when(dataSourceType == Const.DbEngineName.SQLITE) {
    actorSystem.scheduler.scheduleAtFixedRate(1.hour, 1.hour)(() => txManager.optimize())(actorSystem.executionContext)
  }

  object DAO {
    val systemMetadata: SystemMetadataDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.SystemMetadataDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.SystemMetadataDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val user: dao.UserDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.UserDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.UserDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val repository: dao.RepositoryDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.RepositoryDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.jdbc.RepositoryDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val asset: dao.AssetDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.AssetDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.jdbc.AssetDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val folder: dao.FolderDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.FolderDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.FolderDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val album: dao.AlbumDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.AlbumDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.AlbumDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val location: dao.LocationDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.LocationDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.LocationDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val metadataField: dao.UserMetadataFieldDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.MetadataFieldDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.MetadataFieldDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val search: dao.SearchDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.SearchDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.sqlite.SearchDao(app.config)
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val person: dao.PersonDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new PersonDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new PersonDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val face: dao.FaceDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.FaceDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.sqlite.FaceDao(app.config)
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val stats: dao.StatDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.StatDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.StatDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
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

    val system: SystemService = SystemService(app)
    val paseto: PasetoService = PasetoService(app)
    val user: UserService = UserService(app)
    val repository: RepositoryService = RepositoryService(app)
    val metadataExtractor: MetadataExtractionService = MetadataExtractionService()
    val staging: StagingService = StagingService(app)
    val metadata: UserMetadataService = UserMetadataService(app)
    val library: LibraryService = LibraryService(app)
    val search: SearchService = SearchService(app)
    val asset: AssetService = AssetService(app)
    val folder: FolderService = FolderService(app)
    val album: AlbumService = AlbumService(app)
    val location: LocationService = LocationService(app)
    val stats: StatsService = StatsService(app)
    val person: PersonService = PersonService(app)
    val faceDetection: FaceDetectionService = FaceDetectionService(app)
    val faceRecognition: FaceRecognitionService = FaceRecognitionService(app)
    val importPipeline: ImportPipelineService = ImportPipelineService(app)
    val purgePipeline: PurgePipelineService = PurgePipelineService(app)
    val urlService: UrlService = UrlService()
    val geocoder: GeocoderService = GeocoderService(app.config)
    val video: VideoService = VideoService(app.config)

    val fileStore: FileStoreService = fileStoreType match {
      case Const.StorageEngineName.FS => FileSystemStoreService(app)
      // S3-based wants to play as well
      case _ => throw NotImplementedError()
    }
  }

  if dataSourceType == Const.DbEngineName.SQLITE then {
    val dbFolder = new File(dataPath, "db")
    if !dbFolder.exists() then {
      logger.debug("Creating the DB folder for SQLite: " + dbFolder)
      FileUtils.forceMkdir(dbFolder)
    }
  }

  // How many assets the import and purge queues buffer and admit at once, on both engines. It does not decide the concurrent work
  // of an import, which the import dispatcher's threads (`importParallelism`) bound.
  val parallelism: Int = Runtime.getRuntime.availableProcessors()

  // A staged file outlives nothing: whatever is there was left by a run that did not finish. After `parallelism`, which the
  // services read as they are wired up.
  service.staging.clear()

  def setIsInitializedState(): Unit =
    this.isInitialized = service.system.readMetadata.isInitialized
    if !this.isInitialized then logger.warn("Instance NOT YET INITIALIZED!")

  def runMigrations(): Unit =
    if Environment.CURRENT == Environment.Name.TEST then return

    if service.migrationService.migrationRequired then
      logger.warn("Migration is required!")
      service.migrationService.migrate()

  def cleanup(): Unit =
    logger.debug("Cleaning up resources")
    // Before the transaction manager closes the pools: each queue finishes what it has accepted, up to a limit
    service.importPipeline.shutdown()
    service.purgePipeline.shutdown()

    sqliteOptimizing.foreach(_.cancel())
    txManager.optimize()
    txManager.shutdown()
    sqlExplainer.foreach(_.close())

    // This is already done by default and will cause a warning
    // actorSystem.terminate()

  // id -> repository
  var repositoriesById: Map[String, Repository] = Map[String, Repository]()

  def clearState(): Unit =
    repositoriesById = Map.empty

  logger.debug("Altitude Server instance initialized")
