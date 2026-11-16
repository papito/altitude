package altitude.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import altitude.core.actors.ImportStatusWsActor
import altitude.core.dao.jdbc.PersonDao
import altitude.core.dao.jdbc.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.service.AccountVersions
import altitude.core.service.AlbumService
import altitude.core.service.AssetEditService
import altitude.core.service.AssetService
import altitude.core.service.AuditService
import altitude.core.service.ContentSigner
import altitude.core.service.CurationService
import altitude.core.service.EditRenderService
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
import altitude.core.util.DescribeEmbedder
import altitude.core.util.ImportParallelism
import altitude.core.util.ModelFiles
import altitude.core.util.OnnxDescribeEmbedder
import altitude.core.util.OnnxSceneEmbedder
import altitude.core.util.SceneEmbedder

/** The embedder factories build the ONNX Runtime embedders by default; tests pass ones they can switch off. */
class Altitude(
    val dbEngineOverride: Option[String] = None,
    newSceneEmbedder: ModelFiles => SceneEmbedder = OnnxSceneEmbedder(_),
    newDescribeEmbedder: ModelFiles => DescribeEmbedder = OnnxDescribeEmbedder(_)):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.debug(s"Environment is: ${Environment.CURRENT}")

  final val app: Altitude = this

  final val id: Int = scala.util.Random.nextInt(java.lang.Integer.MAX_VALUE)
  logger.debug(s"Initializing Altitude Server application. Instance ID [$id]")

  /**
   * The config before the SQLite URL is added. Dev and prod read theirs through [[Altitude.loadConfig]]: the `ALTITUDE_`
   * variables over the environment's config file over `reference.conf`. Tests read [[Altitude.testConfig]], which takes no config
   * file and none of those variables but `ALTITUDE_MODELS_DIR`; only tests take `CONFIG_FORCE_` environment overrides.
   */
  private val preConfig: Config = Environment.CURRENT match {
    case Environment.Name.TEST => Altitude.testConfig(dbEngineOverride)
    case _ => Altitude.loadConfig()
  }

  /** The Data directory */
  final def dataPath: String = Altitude.dataPathOf(app.config)

  private val SQLITE_URL_PREFIX = "jdbc:sqlite:"

  final private val sqliteRelDbPath = preConfig.getString(Const.Conf.REL_SQLITE_DB_PATH)

  // The SQLite file sits under the test directory in tests, otherwise under the Data directory
  final val config: Config = Environment.CURRENT match {

    case Environment.Name.TEST =>
      val testDir = preConfig.getString(Const.Conf.TEST_DIR)
      val sqliteTestUrl = s"$SQLITE_URL_PREFIX$testDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteTestUrl))
    case _ =>
      val sqliteUrl = s"$SQLITE_URL_PREFIX${Altitude.dataPathOf(preConfig)}${File.separator}$sqliteRelDbPath"
      preConfig.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteUrl))
  }

  /** Whether the first admin user exists, cached from the system metadata by [[setIsInitializedState]] */
  var isInitialized = false

  final private val schemaVersion = 2

  final val dataSourceType: String = config.getString(Const.Conf.DB_ENGINE)
  logger.debug(s"Datasource type: $dataSourceType")

  // Before anything connects: an empty password is a start that was never given one, not one to try the database with
  Altitude.requirePostgresPassword(config)

  /** Whether the session cookie is `Secure` (`server.secure_cookies`); off only for plain HTTP on a LAN or VPN address */
  final val secureCookies: Boolean = config.getBoolean(Const.Conf.SERVER_SECURE_COOKIES)

  final val fileStoreType: String = config.getString(Const.Conf.DEFAULT_STORAGE_ENGINE)
  logger.debug(s"File store type: $fileStoreType")

  private val sqlExplainer: Option[SqlExplainer] =
    Option.when(Environment.devSwitch(config, Const.Conf.DEV_SQL_EXPLAIN)) {
      val file = new File(Environment.ROOT_PATH, SqlExplainer.FILE_NAME)
      logger.info(s"The SQL explain log is on: each new query is explained once, into $file")
      new SqlExplainer(dataSourceType, file)
    }

  // Dev only. Read before the services, which start their queues as they are wired up.
  final val isPipelineRestartEnabled: Boolean = Environment.devSwitch(config, Const.Conf.DEV_RESTART_PIPELINE)
  if isPipelineRestartEnabled then logger.info("A pipeline queue whose stream fails restarts it after a backoff")

  // Startup order: settings service (empty) → transaction manager → DAOs → migration → settings load → importParallelism → model
  // files → actor system → embedders → services. The transaction manager applies the Settings to each transaction, so their
  // service exists first, empty (no Settings applied) until it loads after the migration. The import dispatcher's thread count is
  // a Setting, so Settings load before the actor system. The model files come before the actor system, so a failed download
  // leaves no thread keeping the process up.
  final val settings: SettingsService = SettingsService(app)

  final val txManager: TransactionManager = TransactionManager(app.config, sqlExplainer, () => settings.currentIfLoaded)

  /** On SQLite, the database file's folder: one of the app's own directories */
  final val sqliteDir: Option[Path] = Option.when(dataSourceType == Const.DbEngineName.SQLITE) {
    Path.of(config.getString(Const.Conf.SQLITE_URL).stripPrefix(SQLITE_URL_PREFIX)).toAbsolutePath.getParent
  }

  // Ensured before the first transaction, which the migration below opens
  sqliteDir.foreach {
    dir =>
      logger.debug(s"Ensuring the SQLite database folder: $dir")
      FileUtils.forceMkdir(dir.toFile)

      // The check only warns, so a file system that will not tell its type does not fail the start
      try Altitude.sqliteDiskWarning(dir, Files.getFileStore(dir).`type`()).foreach(logger.warn(_))
      catch case NonFatal(e) => logger.debug(s"The file system of the SQLite database folder $dir is unknown", e)
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

    val assetEdit: dao.AssetEditDao = dataSourceType match {
      case Const.DbEngineName.POSTGRES => new dao.postgres.AssetEditDao(app.config)
      case Const.DbEngineName.SQLITE => new dao.sqlite.AssetEditDao(app.config)
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

  // In every environment, before anything reads the database
  migrationService.migrate()

  settings.load()

  /**
   * The import's thread count and its reason: the Setting as saved at startup, or at 0 what the machine affords, within the
   * config's cap. Each thread holds its own YuNet detector; they share one ArcFace net.
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

  /** Missing model files are downloaded here and the start waits; a download that cannot finish fails the start. */
  final val models: ModelFiles = ModelFiles.ensure(config)

  /** The most undated frames an undated import is compared with: per core, so the machine bounds the read, not triage's size */
  final val undatedSequenceCandidates: Int =
    val cores = Runtime.getRuntime.availableProcessors
    val candidates = Const.Sequence.UNDATED_CANDIDATES_PER_CORE * cores
    logger.info(
      s"Undated Sequence candidates: $candidates (${Const.Sequence.UNDATED_CANDIDATES_PER_CORE} per core, $cores cores)")
    candidates

  // A render decodes a whole original in floats, so Edit renders get their own, smaller pool
  final val editRenderParallelism: Int = Math.max(1, Runtime.getRuntime.availableProcessors / 4)

  val actorSystem: ActorSystem[AltitudeActorSystem.Command] =
    // The phase timeouts of the server's stop (`ServerShutdown`) are set here and not in reference.conf, whose order against
    // Pekko's own reference.conf, which sets the phases, is not fixed: this overlay wins over both.
    val overlay = ConfigFactory.parseString(s"""
      altitude.import-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor.fixed-pool-size = $importParallelism
      }
      altitude.edit-render-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor.fixed-pool-size = $editRenderParallelism
      }
      pekko.coordinated-shutdown.phases {
        service-requests-done.timeout = ${ServerShutdown.REQUESTS_PHASE_TIMEOUT.toSeconds} s
        service-stop.timeout = ${ServerShutdown.STOP_PHASE_TIMEOUT.toSeconds} s
      }
    """)
    ActorSystem[AltitudeActorSystem.Command](AltitudeActorSystem(), "altitude-actor-system", overlay.withFallback(config))

  /** Runs the import's stage work, so the stream actors on the shared default dispatcher only route */
  final val importDispatcher: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup(DispatcherSelector.fromConfig("altitude.import-dispatcher"))

  final val editRenderDispatcher: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup(DispatcherSelector.fromConfig("altitude.edit-render-dispatcher"))

  // SQLite planner statistics, refreshed hourly and at cleanup
  private val sqliteOptimizing: Option[Cancellable] = Option.when(dataSourceType == Const.DbEngineName.SQLITE) {
    actorSystem.scheduler.scheduleAtFixedRate(1.hour, 1.hour)(() => txManager.optimize())(actorSystem.executionContext)
  }

  // Also run at startup. It logs a failure rather than throwing: a scheduled task that throws is not run again
  private val importHistoryPruning: Cancellable =
    actorSystem.scheduler.scheduleAtFixedRate(1.day, 1.day)(() => service.imports.pruneOrLog())(actorSystem.executionContext)

  /** Built before the services, so a model that fails to open fails the start, never an import. Shared by the import threads. */
  final val sceneEmbedder: SceneEmbedder = newSceneEmbedder(models)

  /** Building it loads nothing: the first description opens the text tower, so an import never loads it. */
  final val describeEmbedder: DescribeEmbedder = newDescribeEmbedder(models)

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
    val assetEdit: AssetEditService = AssetEditService(app)
    val editRender: EditRenderService = EditRenderService(app)
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

  // Sizes the queue buffers and the purge's concurrent deletes, not an import's concurrency (`importParallelism`). Must be set
  // before the first use of `service`, whose pipelines read it.
  val parallelism: Int = Runtime.getRuntime.availableProcessors()

  // An account's import status sockets close when its versions change, so a deactivated, deleted or revoked account's feed ends
  service.accountVersions.onChange((accountId, change) => actorSystem ! ImportStatusWsActor.CloseAllFor(accountId, change))

  // A Watch folder's counts are worth reading again when its Import opens or closes
  service.imports.onChange {
    (repositoryId, anImport) =>
      actorSystem ! ImportStatusWsActor.ImportChanged(repositoryId, anImport)
      if anImport.watchFolderId.isDefined && (anImport.total == 0 || !anImport.isInProgress) then
        actorSystem ! ImportStatusWsActor.WatchFoldersChanged(repositoryId)
  }
  service.watchFolder.onChange(repositoryId => actorSystem ! ImportStatusWsActor.WatchFoldersChanged(repositoryId))

  service.staging.clear()

  def setIsInitializedState(): Unit =
    this.isInitialized = service.system.readMetadata.isInitialized

  /**
   * Stops the instance: no more Watch folder scans, then each queue finishes what it accepted, each within the Pipeline shutdown
   * timeout Setting as it reads now, then the embedders and the database close. In a running server it is a task of the
   * coordinated shutdown (`ServerShutdown`), run before the HTTP server stops and the actor system terminates.
   */
  def cleanup(): Unit =
    logger.info("Cleaning up: draining the queues, then closing the embedders and the database")
    // Before the import queue closes, so no Watch folder scan feeds it
    service.watchFolder.stop()
    // Before the pools close: each queue finishes what it has accepted, up to a limit
    service.importPipeline.shutdown()
    service.purgePipeline.shutdown()
    // A render cut off, or not yet queued, is queued again at the next startup
    service.editRender.shutdown()
    // After the import has stopped: its threads run the Scene models
    sceneEmbedder.close()
    // Waits for a description being embedded; one that arrives later is refused
    describeEmbedder.close()

    sqliteOptimizing.foreach(_.cancel())
    importHistoryPruning.cancel(): Unit
    txManager.optimize()
    txManager.shutdown()
    sqlExplainer.foreach(_.close())
    logger.info("Cleaned up")
    // The actor system is not terminated here: the coordinated shutdown terminates it in its last phase

  var repositoriesById: Map[String, Repository] = Map[String, Repository]()

  def clearState(): Unit =
    repositoriesById = Map.empty

  logger.debug("Altitude Server instance initialized")

object Altitude:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The config file of a dev or prod start, when it has one. Dev reads `application-dev.conf` in the root path. Prod reads the
   * file `ALTITUDE_CONFIG` names, a relative one under the root path, and fails when that file does not exist; with the variable
   * unset or empty it reads `application.conf` in the root path, the jar's folder, and a missing file there means the defaults.
   */
  def configFile(environment: String, variables: Map[String, String], rootPath: String): Option[File] =
    def inRoot(name: String): File = Path.of(rootPath).resolve(name).toFile

    environment match {
      case Environment.Name.DEV => Some(inRoot("application-dev.conf")).filter(_.isFile)
      case Environment.Name.PROD =>
        variables.get(Environment.Variable.CONFIG).filter(_.nonEmpty) match {
          case Some(named) =>
            val file = inRoot(named)
            if !file.isFile then
              throw IllegalStateException(s"${Environment.Variable.CONFIG} names a config file that does not exist: $file")
            Some(file)
          case None => Some(inRoot("application.conf")).filter(_.isFile)
        }
      case other => throw IllegalArgumentException(s"The $other environment reads no config file")
    }

  /**
   * The config of a dev or prod start, before the SQLite URL is added: `environment.conf`, which gives a key the value of its
   * `ALTITUDE_` variable, over the environment's config file ([[configFile]]) over `reference.conf`, the whole resolved together.
   * So a variable wins over the file, which wins over the defaults, and a container is configured by its environment alone.
   *
   * The variables are a parameter, the process's own by default, so the loader reads no other environment: a substitution in the
   * config file resolves against the config's keys and these variables, never the rest of the process environment. A variable set
   * to an empty value sets an empty value, as in any HOCON file. `clearDb` passes none: it clears what the dev config file names.
   */
  def loadConfig(
      environment: String = Environment.CURRENT,
      variables: Map[String, String] = Environment.variables,
      rootPath: String = Environment.ROOT_PATH): Config =
    val file = configFile(environment, variables, rootPath)
    file match {
      case Some(found) => logger.info(s"Config file: $found")
      case None => logger.info(s"No config file in $rootPath: the defaults and the environment variables apply")
    }

    val mustExist = ConfigParseOptions.defaults.setAllowMissing(false)
    val ofVariablesAlone = ConfigResolveOptions.defaults.setUseSystemEnvironment(false)
    // Keys of the root, not paths: a variable's name is taken as it is
    val variablesConfig = ConfigValueFactory.fromMap(variables.asJava, "the ALTITUDE_ environment variables").toConfig

    // Only the keys whose variable is set. Layered unresolved, a key with no variable would still put an empty object on its
    // path, and so hide a leftover number at `import.parallelism` in the file from `ImportParallelism.configuredCap`.
    val fromVariables = ConfigFactory
      .parseResources(classOf[Altitude].getClassLoader, "environment.conf", mustExist)
      .resolveWith(variablesConfig, ofVariablesAlone)
      .entrySet
      .asScala
      .foldLeft(ConfigFactory.empty)((config, entry) => config.withValue(entry.getKey, entry.getValue))

    val layered = fromVariables
      .withFallback(file.fold(ConfigFactory.empty)(ConfigFactory.parseFile(_, mustExist)))
      .withFallback(ConfigFactory.defaultReference())

    // The variables are the last fallback while the file's substitutions resolve, then leave the config: they are no keys of it
    val resolved = layered.withFallback(variablesConfig).resolve(ofVariablesAlone)
    variables.keys.foldLeft(resolved)((config, name) => config.withoutPath(ConfigUtil.joinPath(name)))

  /** The Data directory: an absolute `fs.data.dir` as it is, a relative one under the root path (the jar's folder in prod) */
  def dataPathOf(config: Config, rootPath: String = Environment.ROOT_PATH): String =
    FilenameUtils.concat(rootPath, config.getString(Const.Conf.FS_DATA_DIR))

  /**
   * Fails the start on PostgreSQL with an empty password, naming the variable to set: a container whose `.env` sets none is
   * passed an empty one, and the driver's own refusal names neither the variable nor the key.
   */
  def requirePostgresPassword(config: Config): Unit =
    val onPostgres = config.getString(Const.Conf.DB_ENGINE) == Const.DbEngineName.POSTGRES
    if onPostgres && config.getString(Const.Conf.POSTGRES_PASSWORD).isEmpty then
      throw IllegalStateException(
        s"${Const.Conf.DB_ENGINE} is ${Const.DbEngineName.POSTGRES} and the database password is empty: set the " +
          s"ALTITUDE_DB_POSTGRES_PASSWORD environment variable, or ${Const.Conf.POSTGRES_PASSWORD} in the config file")

  // The file store types of a network share, as `java.nio.file.FileStore.type` names them; a `fuse` type is matched by prefix
  private val NETWORK_FILE_STORES = Set("nfs", "nfs4", "cifs", "smb3", "9p", "virtiofs")

  /**
   * What to warn of when the SQLite database's folder is on a file store of this type, if anything. SQLite runs in WAL mode,
   * whose shared-memory file and locks need a local disk: on a network share (NFS, SMB, 9p) or a host folder shared into a
   * container's virtual machine (virtiofs, FUSE) the database can be corrupted or fail to open.
   */
  def sqliteDiskWarning(dir: Path, fileStoreType: String): Option[String] =
    // The root locale: under a Turkish one, CIFS would not lowercase to cifs
    val kind = fileStoreType.toLowerCase(Locale.ROOT)
    Option.when(NETWORK_FILE_STORES.contains(kind) || kind.startsWith("fuse")) {
      s"The SQLite database folder $dir is on a file system of type $fileStoreType, a network share or a host folder shared " +
        "into a container: SQLite's write-ahead log needs a local disk, and the database can be corrupted there. Keep the " +
        "folder on a local disk or in a named volume, or use PostgreSQL."
    }

  /** The test config before the SQLite URL is added; a test that needs config without an instance reads it here. */
  def testConfig(dbEngineOverride: Option[String] = None): Config =
    val relativeTestDir = ConfigFactory.defaultReference().getString(Const.Conf.TEST_DIR)
    val fsDataDirName = ConfigFactory.defaultReference().getString(Const.Conf.FS_DATA_DIR)
    val relativeFsDataDir = FilenameUtils.concat(relativeTestDir, fsDataDirName)

    val config = ConfigFactory
      .systemEnvironmentOverrides()
      .withFallback(ConfigFactory.defaultReference())
      .withValue(Const.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))
      .withValue(Const.Conf.POSTGRES_URL, ConfigValueFactory.fromAnyRef("jdbc:postgresql://localhost:5433/altitude-test"))
      .withValue(Const.Conf.POSTGRES_USER, ConfigValueFactory.fromAnyRef("altitude-test"))
      .withValue(Const.Conf.POSTGRES_PASSWORD, ConfigValueFactory.fromAnyRef("testdba"))
    dbEngineOverride.fold(config)(engine => config.withValue(Const.Conf.DB_ENGINE, ConfigValueFactory.fromAnyRef(engine)))
