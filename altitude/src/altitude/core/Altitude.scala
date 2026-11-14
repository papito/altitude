package altitude.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

import altitude.core.actors.ImportStatusWsActor
import altitude.core.dao.jdbc.PersonDao
import altitude.core.dao.jdbc.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.service.AccountVersions
import altitude.core.service.AlbumService
import altitude.core.service.AssetService
import altitude.core.service.AuditService
import altitude.core.service.ContentSigner
import altitude.core.service.CurationService
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService
import altitude.core.service.FolderService
import altitude.core.service.GeocoderService
import altitude.core.service.ImportPipelineService
import altitude.core.service.ImportService
import altitude.core.service.LibraryService
import altitude.core.service.LocationService
import altitude.core.service.MetadataExtractionService
import altitude.core.service.MigrationService
import altitude.core.service.PasetoService
import altitude.core.service.PersonService
import altitude.core.service.PurgePipelineService
import altitude.core.service.RepositoryService
import altitude.core.service.SearchService
import altitude.core.service.SequenceService
import altitude.core.service.SettingsService
import altitude.core.service.StagingService
import altitude.core.service.StatsService
import altitude.core.service.SystemService
import altitude.core.service.UrlService
import altitude.core.service.UserMetadataService
import altitude.core.service.UserService
import altitude.core.service.VideoService
import altitude.core.service.WatchFolderService
import altitude.core.service.filestore.FileStoreService
import altitude.core.service.filestore.FileSystemStoreService
import altitude.core.transactions.SqlExplainer
import altitude.core.transactions.TransactionManager
import altitude.core.util.ImportParallelism
import altitude.core.util.OnnxSceneEmbedder
import altitude.core.util.SceneEmbedder

/**
 * @param newSceneEmbedder
 *   builds the instance's Scene embedder: the ONNX Runtime one, which production and the dev server use; the test instances build
 *   one they can switch off
 */
class Altitude(val dbEngineOverride: Option[String] = None, newSceneEmbedder: () => SceneEmbedder = () => OnnxSceneEmbedder()):
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
      throw RuntimeException("Unknown environment")

  }

  final def dataPath: String =
    val dataDir: String = app.config.getString(Const.Conf.FS_DATA_DIR)
    FilenameUtils.concat(Environment.ROOT_PATH, dataDir)

  private val SQLITE_URL_PREFIX = "jdbc:sqlite:"

  /** Heroically assemble SQLITE URL based on what we have */
  final private val sqliteRelDbPath = preConfig.getString(Const.Conf.REL_SQLITE_DB_PATH)

  // The database file lives under the test directory under test, and under the data directory otherwise, which is absolute, as
  // every other data path (`dataPath`), since the root is the JAR's folder in production
  final val config: Config = Environment.CURRENT match {

    case Environment.Name.TEST =>
      val testDir = preConfig.getString(Const.Conf.TEST_DIR)
      val sqliteTestUrl = s"$SQLITE_URL_PREFIX$testDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteTestUrl))
    case _ =>
      val dataDir = FilenameUtils.concat(Environment.ROOT_PATH, preConfig.getString(Const.Conf.FS_DATA_DIR))
      val sqliteUrl = s"$SQLITE_URL_PREFIX$dataDir${File.separator}$sqliteRelDbPath"
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

  // Startup order: settings service (empty) → transaction manager → DAOs → migration → settings load → importParallelism → actor
  // system → Scene embedder → services. The transaction manager reads the Settings an Admin tunes on the Settings screen for the
  // transactions it begins, so their service comes first; it is loaded once the schema is in place, and until then a transaction
  // sets none. The import dispatcher's thread count starts from a Setting, so the database must be current and read before the
  // actor system is built.
  final val settings: SettingsService = SettingsService(app)

  final val txManager: TransactionManager = TransactionManager(app.config, sqlExplainer, () => settings.currentIfLoaded)

  /** The folder of the SQLite database file, on SQLite: one of the directories the app stores into */
  final val sqliteDir: Option[Path] = Option.when(dataSourceType == Const.DbEngineName.SQLITE) {
    Path.of(config.getString(Const.Conf.SQLITE_URL).stripPrefix(SQLITE_URL_PREFIX)).toAbsolutePath.getParent
  }

  // Ensured before the first transaction, which the migration below opens
  sqliteDir.foreach {
    dir =>
      logger.debug(s"Ensuring the SQLite database folder: $dir")
      FileUtils.forceMkdir(dir.toFile)
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

    val repositoryGrant: dao.RepositoryGrantDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.RepositoryGrantDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.RepositoryGrantDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val auditEvent: dao.AuditEventDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.AuditEventDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.AuditEventDao(app.config) with dao.sqlite.SqliteOverrides
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

    val imports: dao.ImportDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.ImportDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.ImportDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val importFile: dao.ImportFileDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.ImportFileDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.ImportFileDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val watchFolder: dao.WatchFolderDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.WatchFolderDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.WatchFolderDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val watchFolderFile: dao.WatchFolderFileDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.WatchFolderFileDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.WatchFolderFileDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val setting: dao.SettingDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.jdbc.SettingDao(app.config) with dao.postgres.PostgresOverrides
      case Const.DbEngineName.SQLITE => new dao.jdbc.SettingDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }
  }

  final val migrationService: MigrationService =
    MigrationService(app, currentVersion = schemaVersion, migrationsDir = s"/migrations/$dataSourceType")

  // A fresh or behind database is migrated in every environment, before anything reads it
  migrationService.migrate()

  settings.load()

  /**
   * How many threads do the import's work, and why: the `import.parallelism` Setting as it was saved when the server started (a
   * Restart setting), or, at 0, what the machine affords, within the cap in the server config (see `ImportParallelism`). Each of
   * them holds its own YuNet face detector, and they share one ArcFace recognition net.
   */
  final val importParallelismResolution: ImportParallelism.Resolution = ImportParallelism.resolve(
    settings.atStartup.importParallelism,
    Runtime.getRuntime.availableProcessors,
    // The machine's memory, or the container's limit in a container
    ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean].getTotalMemorySize,
    ImportParallelism.configuredCap(config)
  )
  final val importParallelism: Int = importParallelismResolution.value
  logger.info(s"Import parallelism: ${importParallelismResolution.summary}")

  /**
   * The most undated frames one undated import is compared with (`SequenceService.assign`): `Const.Sequence` per core, which
   * bounds the read's cost by the machine rather than by what triage holds. Computed once, at startup, as `importParallelism` is.
   */
  final val undatedSequenceCandidates: Int =
    val cores = Runtime.getRuntime.availableProcessors
    val candidates = Const.Sequence.UNDATED_CANDIDATES_PER_CORE * cores
    logger.info(
      s"Undated Sequence candidates: $candidates (${Const.Sequence.UNDATED_CANDIDATES_PER_CORE} per core, $cores cores)")
    candidates

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
   * dispatcher, which the import status feed, the SQLite optimize schedule and the Import history prune share, only route
   */
  final val importDispatcher: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup(DispatcherSelector.fromConfig("altitude.import-dispatcher"))

  // SQLite refreshes its planner statistics hourly, on the write connection between transactions, and once more at cleanup
  private val sqliteOptimizing: Option[Cancellable] = Option.when(dataSourceType == Const.DbEngineName.SQLITE) {
    actorSystem.scheduler.scheduleAtFixedRate(1.hour, 1.hour)(() => txManager.optimize())(actorSystem.executionContext)
  }

  // The Import history is pruned daily of the Imports past the `import.history.days` Setting, and once at startup (`App`); the
  // Watch folders forget the files long absent from their directories with it. A prune that fails is logged and not thrown: a
  // scheduled task that throws is not run again
  private val importHistoryPruning: Cancellable =
    actorSystem.scheduler.scheduleAtFixedRate(1.day, 1.day)(() => service.imports.pruneOrLog())(actorSystem.executionContext)

  /**
   * The Scene models every imported still image is embedded with (`AssetService.measureImage`), built before the services, so a
   * missing model file fails the start and never an import, as the ArcFace pool's file does. The import threads share it, and
   * `cleanup` closes it.
   */
  final val sceneEmbedder: SceneEmbedder = newSceneEmbedder()

  object service {
    val system: SystemService = SystemService(app)
    val contentSigner: ContentSigner = ContentSigner(system.loadOrCreateSigningKey())
    val paseto: PasetoService = PasetoService(app)
    val accountVersions: AccountVersions = AccountVersions.processWide
    val user: UserService = UserService(app)
    val repository: RepositoryService = RepositoryService(app)
    val audit: AuditService = AuditService(app)
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
    val sequence: SequenceService = SequenceService(app, undatedSequenceCandidates)
    val curation: CurationService = CurationService(app)
    val person: PersonService = PersonService(app)
    val faceDetection: FaceDetectionService = FaceDetectionService(app)
    val faceRecognition: FaceRecognitionService = FaceRecognitionService(app)
    val imports: ImportService = ImportService(app)
    val watchFolder: WatchFolderService = WatchFolderService(app)
    val importPipeline: ImportPipelineService = ImportPipelineService(app)
    val purgePipeline: PurgePipelineService = PurgePipelineService(app)
    val urlService: UrlService = UrlService()
    val geocoder: GeocoderService = GeocoderService(() => settings.current)
    val video: VideoService = VideoService(() => settings.current)

    val fileStore: FileStoreService = fileStoreType match {
      case Const.StorageEngineName.FS => FileSystemStoreService(app)
      // S3-based wants to play as well
      case _ => throw NotImplementedError()
    }
  }

  // Sizes the buffers of the import and purge queues (twice this many assets each), on both engines. It does not decide the
  // concurrent work of an import, which the import dispatcher's threads (`importParallelism`) bound.
  val parallelism: Int = Runtime.getRuntime.availableProcessors()

  // An account's import status sockets close when its versions change, so a deactivated, deleted or revoked account's feed ends
  service.accountVersions.onChange((accountId, change) => actorSystem ! ImportStatusWsActor.CloseAllFor(accountId, change))

  // The import status sockets of a Library are told of each change to one of its Imports, once it has committed, and of a
  // change to what its Watch folders show: one added, edited, paused, resumed or removed, a scan of one started or ended, and
  // an Import of one opened or closed, which is when its counts are worth reading again
  service.imports.onChange {
    (repositoryId, anImport) =>
      actorSystem ! ImportStatusWsActor.ImportChanged(repositoryId, anImport)
      if anImport.watchFolderId.isDefined && (anImport.total == 0 || !anImport.isInProgress) then
        actorSystem ! ImportStatusWsActor.WatchFoldersChanged(repositoryId)
  }
  service.watchFolder.onChange(repositoryId => actorSystem ! ImportStatusWsActor.WatchFoldersChanged(repositoryId))

  // A staged file outlives nothing: whatever is there was left by a run that did not finish. After `parallelism`, which the
  // services read as they are wired up.
  service.staging.clear()

  def setIsInitializedState(): Unit =
    this.isInitialized = service.system.readMetadata.isInitialized
    if !this.isInitialized then logger.warn("Instance NOT YET INITIALIZED!")

  def cleanup(): Unit =
    logger.debug("Cleaning up resources")
    // Before the import queue closes: no scan of a Watch folder starts, and one that is running offers the queue nothing more
    service.watchFolder.stop()
    // Before the transaction manager closes the pools: each queue finishes what it has accepted, up to a limit
    service.importPipeline.shutdown()
    service.purgePipeline.shutdown()
    // After the import has stopped: its threads run the Scene models
    sceneEmbedder.close()

    sqliteOptimizing.foreach(_.cancel())
    importHistoryPruning.cancel(): Unit
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
