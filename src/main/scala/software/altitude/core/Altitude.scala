package software.altitude.core

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import net.codingwell.scalaguice.InjectorExtensions._
import net.codingwell.scalaguice.ScalaModule
import org.slf4j.LoggerFactory
import software.altitude.core.dao._
import software.altitude.core.service._
import software.altitude.core.service.filestore.FileStoreService
import software.altitude.core.service.filestore.FileSystemStoreService
import software.altitude.core.service.migration._
import software.altitude.core.transactions._
import software.altitude.core.{Const => C}

import java.sql.DriverManager

class Altitude(val configOverride: Map[String, Any] = Map()) extends AltitudeCoreApp  {
  private final val log = LoggerFactory.getLogger(getClass)
  log.info(s"Initializing Altitude Server application instance with ID [$id]")

  final val app: Altitude = this

  final val fileStoreType = config.fileStoreType
  log.info(s"File store type: $fileStoreType")

  /**
   * At this point determine which data access classes we are loading, which
   * transaction manager we are using for the data sources of choice, load the drivers,
   * etc.
   */
  override val injector: Injector = Guice.createInjector(new InjectionModule)

  /**
   * Injected transaction manager, determined based on our database.
   */
  override val txManager: AbstractTransactionManager = app.injector.instance[AbstractTransactionManager]

  object service {
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

    private final val schemaVersion = 1

    val migrationService: MigrationService = dataSourceType match {
      case C.DatasourceType.SQLITE => new MigrationService(app) with JdbcMigrationService with SqliteMigration {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/server/sqlite"
      }
      case C.DatasourceType.POSTGRES => new MigrationService(app) with JdbcMigrationService with PostgresMigration {
        override final val CURRENT_VERSION = schemaVersion
        override final val MIGRATIONS_DIR = "/migrations/server/postgres"
      }
    }
  }

  /**
   * Inject dependencies
   */
  class InjectionModule extends AbstractModule with ScalaModule  {
    override def configure(): Unit = {

      dataSourceType match {
        case C.DatasourceType.POSTGRES =>
          DriverManager.registerDriver(new org.postgresql.Driver)

          val jdbcTxManager = new software.altitude.core.transactions.JdbcTransactionManager(app)
          bind[AbstractTransactionManager].toInstance(jdbcTxManager)
          bind[JdbcTransactionManager].toInstance(jdbcTxManager)

          bind[UserDao].toInstance(new jdbc.UserDao(app) with dao.postgres.Postgres)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app) with dao.postgres.Postgres)
          bind[RepositoryDao].toInstance(new postgres.RepositoryDao(app))
          bind[AssetDao].toInstance(new postgres.AssetDao(app) with dao.postgres.Postgres)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app) with dao.postgres.Postgres)
          bind[StatDao].toInstance(new jdbc.StatDao(app) with dao.postgres.Postgres with dao.jdbc.Stats)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app) with dao.postgres.Postgres)
          bind[SearchDao].toInstance(new postgres.SearchDao(app))

        case C.DatasourceType.SQLITE =>
          DriverManager.registerDriver(new org.sqlite.JDBC)

          val jdbcTxManager = new SqliteTransactionManager(app)
          bind[AbstractTransactionManager].toInstance(jdbcTxManager)
          bind[JdbcTransactionManager].toInstance(jdbcTxManager)

          bind[UserDao].toInstance(new jdbc.UserDao(app) with dao.sqlite.Sqlite)
          bind[MigrationDao].toInstance(new jdbc.MigrationDao(app) with dao.sqlite.Sqlite)
          bind[RepositoryDao].toInstance(new jdbc.RepositoryDao(app) with dao.sqlite.Sqlite)
          bind[AssetDao].toInstance(new jdbc.AssetDao(app) with dao.sqlite.Sqlite)
          bind[FolderDao].toInstance(new jdbc.FolderDao(app) with dao.sqlite.Sqlite)
          bind[StatDao].toInstance(new jdbc.StatDao(app) with dao.sqlite.Sqlite with dao.jdbc.Stats)
          bind[MetadataFieldDao].toInstance(new jdbc.MetadataFieldDao(app) with dao.sqlite.Sqlite)
          bind[SearchDao].toInstance(new sqlite.SearchDao(app))

        case _ =>
          throw new IllegalArgumentException(s"Do not know of datasource [$dataSourceType]")
      }
    }
  }

  def freeResources(): Unit = {
    txManager.freeResources()
  }

  override def runMigrations(): Unit = {
    if (service.migrationService.migrationRequired) {
      log.warn("Migration is required!")
      if (Environment.ENV != Environment.TEST) {
        service.migrationService.migrate()
      }
    }
  }

  //F FIXME: temporary
  val USER_ID = "100000000000000000000000000000000000"
  val REPOSITORY_ID = "100000000000000000000000000000000000"
  log.info("Altitude Server instance initialized")
}
