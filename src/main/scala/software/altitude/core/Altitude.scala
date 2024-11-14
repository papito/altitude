package software.altitude.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.auth.strategies.LocalDevRememberMeStrategy
import software.altitude.core.auth.strategies.RememberMeStrategy
import software.altitude.core.auth.strategies.TestRememberMeStrategy
import software.altitude.core.auth.strategies.UserPasswordStrategy
import software.altitude.core.dao._
import software.altitude.core.models.User
import software.altitude.core.service._
import software.altitude.core.service.filestore.FileStoreService
import software.altitude.core.service.filestore.FileSystemStoreService
import software.altitude.core.transactions._
import software.altitude.core.{Const => C}

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class Altitude(val dbEngineOverride: Option[String] = None)  {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)
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
   * Default reference configs are in
   *  src/main/resources/reference.conf
   * and
   *  src/test/resources/reference.conf
   *
   *  For DEV and PROD, application*.conf files have the final say -
   *  and are in the root of the project (and along the live JAR in release)
   */

  // the config before final actual config as we need to dynamically figure out some values later
  private val preConfig: Config = Environment.CURRENT match {
    case Environment.Name.DEV =>
      ConfigFactory.parseFile(new File("application-dev.conf"))
        .withFallback(ConfigFactory.defaultReference())

    case Environment.Name.PROD =>
      ConfigFactory.parseFile(new File("application.conf"))
        .withFallback(ConfigFactory.defaultReference())

    case Environment.Name.TEST =>
      val relativeTestDir = ConfigFactory.defaultReference().getString(C.Conf.TEST_DIR)
      val fsDataDirName = ConfigFactory.defaultReference().getString(C.Conf.FS_DATA_DIR)
      val relativeFsDataDir = FilenameUtils.concat(relativeTestDir, fsDataDirName)

      dbEngineOverride match {
        case Some(ds) =>
          ConfigFactory.systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(C.Conf.DB_ENGINE, ConfigValueFactory.fromAnyRef(ds))
            .withValue(C.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))

        case None =>
          ConfigFactory.systemEnvironmentOverrides()
            .withFallback(ConfigFactory.defaultReference())
            .withValue(C.Conf.FS_DATA_DIR, ConfigValueFactory.fromAnyRef(relativeFsDataDir))
      }

    case _ =>
      throw new RuntimeException("Unknown environment")

  }

  final def dataPath: String = {
    val dataDir: String = app.config.getString(C.Conf.FS_DATA_DIR)
    FilenameUtils.concat(Environment.ROOT_PATH, dataDir)
  }

  /**
   * Heroically assemble SQLITE URL based on what we have
   */
  private final val sqliteRelDbPath = preConfig.getString(C.Conf.REL_SQLITE_DB_PATH)

  final val config: Config = Environment.CURRENT match {

    case Environment.Name.TEST =>
      val testDir = preConfig.getString(C.Conf.TEST_DIR)
      val sqliteTestUrl = s"jdbc:sqlite:$testDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(C.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteTestUrl))

    case _ =>
      val dataDir = preConfig.getString(C.Conf.FS_DATA_DIR)
      val sqliteUrl = s"jdbc:sqlite:$dataDir${File.separator}$sqliteRelDbPath"
      preConfig.withValue(C.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(sqliteUrl))
  }

  /**
   * Has the first admin user been created?
   * This flag is loaded from the system metadata table upon start and then
   * cached for the lifetime of the application instance.
   *
   * This is to avoid getting the value from the database every time we need it.
   *
   * See: setIsInitializedState() in this file.
   */
  var isInitialized = false

  private final val schemaVersion = 1

  final val dataSourceType: String = config.getString(C.Conf.DB_ENGINE)
  logger.info(s"Datasource type: $dataSourceType")

  final val fileStoreType: String =  config.getString(C.Conf.DEFAULT_STORAGE_ENGINE)
  logger.info(s"File store type: $fileStoreType")

  /**
   * App thread pool, whatever it is needed for
   */
  private val maxThreads: Int = dataSourceType match {
    case C.DbEngineName.POSTGRES => 1 // Runtime.getRuntime.availableProcessors()
    case C.DbEngineName.SQLITE => 1 // SQLite is single-threaded
  }
  logger.info(s"Available processors: $maxThreads")

  val executorService: ExecutorService = Executors.newFixedThreadPool(maxThreads)
  logger.info("Executor service initialized")

  final val txManager: TransactionManager = new software.altitude.core.transactions.TransactionManager(app.config)

  /**
   * Scentry strategies differ from environment to environment.
   * Production strategy is different from development and test.
   * In dev, since the cookie store is cleared on every hot reload, logging in every time is a pain.
   */
  val scentryStrategies: List[(String, Class[_ <: ScentryStrategy[User]])] = Environment.CURRENT match {
    case Environment.Name.PROD => List(
//      ("RememberMeStrategy", classOf[LocalDevRememberMeStrategy])
      ("UserPasswordStrategy", classOf[UserPasswordStrategy]),
      ("RememberMeStrategy", classOf[RememberMeStrategy])
    )
    case Environment.Name.DEV => List(
      ("RememberMeStrategy", classOf[LocalDevRememberMeStrategy])
    )
    case Environment.Name.TEST => List(
      ("RememberMeStrategy", classOf[TestRememberMeStrategy])
    )
    case _ => throw new RuntimeException("Unknown environment")
  }

  object DAO {
    val systemMetadata: SystemMetadataDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.SystemMetadataDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.SystemMetadataDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val user: UserDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.UserDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.UserDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val userToken: UserTokenDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.UserTokenDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.UserTokenDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val repository: RepositoryDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new postgres.RepositoryDao(app.config)
      case C.DbEngineName.SQLITE => new jdbc.RepositoryDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val asset: AssetDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new postgres.AssetDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.AssetDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val folder: FolderDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.FolderDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.FolderDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val stats: StatDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.StatDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.StatDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val metadataField: UserMetadataFieldDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.MetadataFieldDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.MetadataFieldDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val search: SearchDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new postgres.SearchDao(app.config)
      case C.DbEngineName.SQLITE => new sqlite.SearchDao(app.config)
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val person: PersonDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new postgres.PersonDao(app.config)
      case C.DbEngineName.SQLITE => new sqlite.PersonDao(app.config)
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }

    val face: FaceDao = dataSourceType match {
      case C.DbEngineName.POSTGRES => new jdbc.FaceDao(app.config) with dao.postgres.PostgresOverrides
      case C.DbEngineName.SQLITE => new jdbc.FaceDao(app.config) with dao.sqlite.SqliteOverrides
      case _ => throw new IllegalArgumentException(s"Unknown datasource [$dataSourceType]")
    }
  }

  object service {
    val migrationService: MigrationService = dataSourceType match {
      case C.DbEngineName.SQLITE => new MigrationService(app) {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/sqlite"
      }
      case C.DbEngineName.POSTGRES => new MigrationService(app) {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/postgres"
      }
    }

    val system = new SystemService(app)
    val user = new UserService(app)
    val repository = new RepositoryService(app)
    val assetImport = new AssetImportService(app)
    val metadataExtractor = new MetadataExtractionService
    val metadata = new UserMetadataService(app)
    val library = new LibraryService(app)
    val search = new SearchService(app)
    val asset = new AssetService(app)
    val folder = new FolderService(app)
    val stats = new StatsService(app)
    val person = new PersonService(app)
    val faceDetection = new FaceDetectionService()
    val faceRecognition = new FaceRecognitionService(app)
    val faceCache = new FaceCacheService(app)

    val fileStore: FileStoreService = fileStoreType match {
      case C.StorageEngineName.FS => new FileSystemStoreService(app)
      // S3-based file store bigly wants to be here
      case _ => throw new NotImplementedError
    }

    if (dataSourceType == C.DbEngineName.SQLITE) {
      val dbFolder = new File(dataPath, "db")
      if (!dbFolder.exists()) {
        logger.info("Creating the DB folder for SQLite: " + dbFolder)
        FileUtils.forceMkdir(dbFolder)
      }
    }
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

  logger.info("Altitude Server instance initialized")
}
