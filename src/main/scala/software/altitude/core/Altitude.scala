package software.altitude.core

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import net.codingwell.scalaguice.ScalaModule
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

import java.sql.DriverManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Altitude(val configOverride: Map[String, Any] = Map())  {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Environment is: ${Environment.ENV}")

  final val app: Altitude = this

  // ID for this application
  final val id: Int = scala.util.Random.nextInt(java.lang.Integer.MAX_VALUE)
  logger.info(s"Initializing Altitude Server application. Instance ID [$id]")

  final val config = new Configuration(configOverride = configOverride)

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

  private final val dataSourceType = config.datasourceType
  logger.info(s"Datasource type: $dataSourceType")

  final val fileStoreType = config.fileStoreType
  logger.info(s"File store type: $fileStoreType")

  // app thread pool, whatever it is needed for
  private val maxThreads: Int = Runtime.getRuntime.availableProcessors()
  logger.info(s"Available processors: $maxThreads")
  val executorService: ExecutorService = Executors.newFixedThreadPool(maxThreads)
  logger.info("Executor service initialized")

  /**
   * At this point determine which data access and storage classes we are loading,
   * based on data source
   */
  final val injector: Injector = Guice.createInjector(new InjectionModule)

  final val txManager: TransactionManager = new software.altitude.core.transactions.TransactionManager(app.config)

  object service {

    val migrationService: MigrationService = dataSourceType match {
      case C.DatasourceType.SQLITE => new MigrationService(app) {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/sqlite"
      }
      case C.DatasourceType.POSTGRES => new MigrationService(app) {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/postgres"
      }
    }

    val system = new SystemService(app)
    val user = new UserService(app)
    val repository = new RepositoryService(app)
    val assetImport = new AssetImportService(app)
    val metadataExtractor = new TikaMetadataExtractionService
    val metadata = new MetadataService(app)
    val library = new LibraryService(app)
    val search = new SearchService(app)
    val asset = new AssetService(app)
    val folder = new FolderService(app)
    val stats = new StatsService(app)

    val fileStore: FileStoreService = fileStoreType match {
      case C.FileStoreType.FS => new FileSystemStoreService(app)
      // S3-based file store bigly wants to be here
      case _ => throw new NotImplementedError
    }
  }

  /**
   * Scentry strategies differ from environment to environment.
   * Production strategy is different from development and test.
   * In dev, since the cookie store is cleared on every hot reload, logging in every time is a pain.
   */
  val scentryStrategies: List[(String, Class[_ <: ScentryStrategy[User]])] = Environment.ENV match {
    case Environment.PROD => List(
      ("UserPasswordStrategy", classOf[UserPasswordStrategy]),
      ("RememberMeStrategy", classOf[RememberMeStrategy])
    )
    case Environment.DEV => List(
      ("RememberMeStrategy", classOf[LocalDevRememberMeStrategy])
    )
    case Environment.TEST => List(
      ("RememberMeStrategy", classOf[TestRememberMeStrategy])
    )
    case _ => throw new RuntimeException("Unknown environment")
  }

  /**
   * Inject data access classes based on the data source type
   */
  private class InjectionModule extends AbstractModule with ScalaModule  {
    override def configure(): Unit = {

      dataSourceType match {
        case C.DatasourceType.POSTGRES =>
          DriverManager.registerDriver(new org.postgresql.Driver)

          bind[SystemMetadataDao].toInstance(new jdbc.SystemMetadataDao(app.config) with dao.postgres.PostgresOverrides)
          bind[UserDao].toInstance(new jdbc.UserDao(app.config) with dao.postgres.PostgresOverrides)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app.config) with dao.postgres.PostgresOverrides)
          bind[RepositoryDao].toInstance(new postgres.RepositoryDao(app.config))
          bind[AssetDao].toInstance(new postgres.AssetDao(app.config) with dao.postgres.PostgresOverrides)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app.config) with dao.postgres.PostgresOverrides)
          bind[StatDao].toInstance(new jdbc.StatDao(app.config) with dao.postgres.PostgresOverrides)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app.config) with dao.postgres.PostgresOverrides)
          bind[SearchDao].toInstance(new postgres.SearchDao(app.config))

        case C.DatasourceType.SQLITE =>
          DriverManager.registerDriver(new org.sqlite.JDBC)

          bind[SystemMetadataDao].toInstance(new jdbc.SystemMetadataDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[UserDao].toInstance(new jdbc.UserDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[RepositoryDao].toInstance(new jdbc.RepositoryDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[AssetDao].toInstance(new jdbc.AssetDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[StatDao].toInstance(new jdbc.StatDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app.config) with dao.sqlite.SqliteOverrides)
          bind[SearchDao].toInstance(new sqlite.SearchDao(app.config))

        case _ =>
          throw new IllegalArgumentException(s"Do not know of datasource [$dataSourceType]")
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
    if (service.migrationService.migrationRequired) {
      logger.warn("Migration is required!")
      if (Environment.ENV != Environment.TEST) {
        service.migrationService.migrate()
      }
    }
  }

  logger.info("Altitude Server instance initialized")
}
