package software.altitude.core

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import net.codingwell.scalaguice.ScalaModule
import org.scalatra.auth.ScentryStrategy
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
import java.util.concurrent.{ExecutorService, Executors}

class Altitude(val configOverride: Map[String, Any] = Map()) extends AltitudeAppContext  {
  private final val logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Initializing Altitude Server application instance with ID [$id]")

  final val app: Altitude = this

  var isInitialized = false

  final val fileStoreType = config.fileStoreType
  logger.info(s"File store type: $fileStoreType")

  /**
   * At this point determine which data access classes we are loading, which
   * transaction manager we are using for the data sources of choice, load the drivers,
   * etc.
   */
  override val injector: Injector = Guice.createInjector(new InjectionModule)

  /**
   * Injected transaction manager, determined based on our database.
   */
  override val txManager: TransactionManager = new software.altitude.core.transactions.TransactionManager(app)

  private final val schemaVersion = 1

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
      case _ => throw new NotImplementedError
    }
  }

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
   * Inject dependencies
   */
  private class InjectionModule extends AbstractModule with ScalaModule  {
    override def configure(): Unit = {

      dataSourceType match {
        case C.DatasourceType.POSTGRES =>
          DriverManager.registerDriver(new org.postgresql.Driver)

          bind[SystemMetadataDao].toInstance(new jdbc.SystemMetadataDao(app) with dao.postgres.PostgresOverrides)
          bind[UserDao].toInstance(new jdbc.UserDao(app) with dao.postgres.PostgresOverrides)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app) with dao.postgres.PostgresOverrides)
          bind[RepositoryDao].toInstance(new postgres.RepositoryDao(app))
          bind[AssetDao].toInstance(new postgres.AssetDao(app) with dao.postgres.PostgresOverrides)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app) with dao.postgres.PostgresOverrides)
          bind[StatDao].toInstance(new jdbc.StatDao(app) with dao.postgres.PostgresOverrides)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app) with dao.postgres.PostgresOverrides)
          bind[SearchDao].toInstance(new postgres.SearchDao(app))

        case C.DatasourceType.SQLITE =>
          DriverManager.registerDriver(new org.sqlite.JDBC)

          bind[SystemMetadataDao].toInstance(new jdbc.SystemMetadataDao(app) with dao.sqlite.SqliteOverrides)
          bind[UserDao].toInstance(new jdbc.UserDao(app) with dao.sqlite.SqliteOverrides)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app) with dao.sqlite.SqliteOverrides)
          bind[RepositoryDao].toInstance(new jdbc.RepositoryDao(app) with dao.sqlite.SqliteOverrides)
          bind[AssetDao].toInstance(new jdbc.AssetDao(app) with dao.sqlite.SqliteOverrides)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app) with dao.sqlite.SqliteOverrides)
          bind[StatDao].toInstance(new jdbc.StatDao(app) with dao.sqlite.SqliteOverrides)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app) with dao.sqlite.SqliteOverrides)
          bind[SearchDao].toInstance(new sqlite.SearchDao(app))

        case _ =>
          throw new IllegalArgumentException(s"Do not know of datasource [$dataSourceType]")
      }
    }
  }

  def freeResources(): Unit = {}

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
