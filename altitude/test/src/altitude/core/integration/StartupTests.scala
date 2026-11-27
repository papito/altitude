package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.SwitchableDescribeEmbedder
import altitude.test.TestAltitudeApp
import altitude.test.TestFocus
import com.typesafe.config.ConfigValueFactory
import java.nio.file.Files
import java.sql.SQLException
import java.time.Instant
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.models.SceneModel
import altitude.core.models.Settings
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolderSpec
import altitude.core.service.ContentSigner
import altitude.core.service.MigrationService
import altitude.core.suites.TestDatabase
import altitude.core.transactions.TransactionManager
import altitude.core.util.Sha256

/**
 * Startup against the test database: a fresh database gets the schema with its version in one transaction, one holding tables but
 * no Altitude schema is refused, an existing one is migrated only when behind, the Settings load, and the import dispatcher is
 * sized from `import.parallelism` as saved, so a change applies on restart.
 */
@DoNotDiscover class StartupTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  override def afterEach(): Unit = {
    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "0"))
    super.afterEach()
  }

  /** A second instance against the test database, shut down afterwards; nothing else may run against it meanwhile */
  private def withFreshInstance[T](f: Altitude => T): T = {
    val other = TestAltitudeApp.build(testApp.dataSourceType)
    try f(other)
    finally {
      other.cleanup()
      other.actorSystem.terminate()
      Await.ready(other.actorSystem.whenTerminated, 10.seconds)
    }
  }

  test("A test instance embeds no description until a test turns the Describe model on") {

    /**
     * Setup:
     *
     * The suite's test instance, whose suite leaves the Describe model off.
     *
     * Assertions:
     *
     * A description has no embedding, except inside `withDescribeModel`, where it has SigLIP 2's dimensions. The model is off
     * again afterwards.
     */
    testApp.describeEmbedder.embed("a cactus") shouldBe None

    withDescribeModel {
      testApp.describeEmbedder.embed("a cactus").value.values.length shouldBe SceneModel.Siglip2.dimensions
    }

    testApp.describeEmbedder.embed("a cactus") shouldBe None
  }

  test("An instance's cleanup closes its Describe embedder") {

    /**
     * Setup:
     *
     * A second instance with its Describe model on, which has embedded a description and so holds the text tower open.
     *
     * Assertions:
     *
     * After the instance's cleanup its embedder refuses every description, the one it already embedded included.
     */
    val other = TestAltitudeApp.build(testApp.dataSourceType)
    try {
      other.describeEmbedder match {
        case switchable: SwitchableDescribeEmbedder => switchable.turnOn()
        case embedder => fail(s"The test instance's Describe embedder is a ${embedder.getClass.getName}")
      }
      other.describeEmbedder.embed("a cactus").value
    } finally {
      other.cleanup()
      other.actorSystem.terminate()
      Await.ready(other.actorSystem.whenTerminated, 10.seconds)
    }

    an[IllegalStateException] should be thrownBy other.describeEmbedder.embed("a man in a suit")
    an[IllegalStateException] should be thrownBy other.describeEmbedder.embed("a cactus")
  }

  test("A new instance against a migrated database keeps the schema version and starts with the Settings loaded") {

    /**
     * Setup:
     *
     * The test database at the current schema version, with the import parallelism at its seed of 0, and a second instance
     * started against it.
     *
     * Assertions:
     *
     * The new instance finds the same schema version. Its startup Settings are loaded with the import parallelism at 0, so the
     * machine's resources size its import dispatcher, at no fewer than two threads.
     */
    val versionBefore = testApp.service.system.readMetadata.version

    withFreshInstance {
      other =>
        other.service.system.readMetadata.version shouldBe versionBefore
        other.settings.atStartup.importParallelism shouldBe 0
        other.importParallelism should be >= 2
    }
  }

  test("A new instance against a migrated database signs with the stored signing key rather than a new one") {

    /**
     * Setup:
     *
     * The signing key the test database holds, then a second instance started against it. The key is created by asking for it,
     * because the suite bundle resets the database after the running instance has started.
     *
     * Assertions:
     *
     * The new instance reads the same key, and its signer signs a content path as a signer with that key does.
     */
    val key = testApp.service.system.loadOrCreateSigningKey()
    val path = "/content/r/repo/preview/item"
    val expiresAt = Instant.now.plusSeconds(60)

    withFreshInstance {
      other =>
        other.service.system.loadOrCreateSigningKey() shouldBe key
        other.service.contentSigner.sign(path, expiresAt) shouldBe ContentSigner(key).sign(path, expiresAt)
    }
  }

  test("A new instance against a fresh database creates the schema and seeds the Settings") {

    /**
     * Setup:
     *
     * The test database emptied of every table, then a second instance started against it. The database is reset afterwards for
     * the rest of the suite.
     *
     * Assertions:
     *
     * The new instance creates the schema stamped with the build's schema version, which the script itself does not seed, and
     * seeds the Settings, the import parallelism at its default of 0.
     */
    TestDatabase.wipe(testApp)

    try {
      withFreshInstance {
        other =>
          other.service.system.readMetadata.version shouldBe other.schemaVersion
          other.settings.current.importParallelism shouldBe 0
      }
    } finally {
      TestDatabase.reset(testApp)
    }
  }

  /**
   * The tables and views of the schema the migration creates into, by name, read through other catalogs than the migration's own
   * check: none on a database wiped empty
   */
  private def tablesInSchema: List[String] = testApp.txManager.asReadOnly {
    val sql = testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES =>
        "SELECT table_name AS name FROM information_schema.tables WHERE table_schema = 'public'"
      // sqlite-vector creates a table of its own in every database it is loaded into
      case _ =>
        """SELECT name FROM pragma_table_list
            WHERE schema = 'main' AND name NOT LIKE 'sqlite_%' AND name <> '_sqliteai_vector'"""
    }
    query(sql).map(_("name").toString).sorted
  }

  /**
   * Runs `f` with a transaction manager of its own over the test database, whose connections have `searchPath` as their search
   * path, and shuts it down afterwards
   */
  private def withSearchPath[T](searchPath: String)(f: TransactionManager => T): T = {
    val options = s"${testApp.config.getString(Const.Conf.POSTGRES_OPTIONS)} -c search_path=$searchPath"
    val manager =
      new TransactionManager(
        testApp.config.withValue(Const.Conf.POSTGRES_OPTIONS, ConfigValueFactory.fromAnyRef(options)),
        None,
        () => None)
    try f(manager)
    finally manager.shutdown()
  }

  test("A start against a database holding another application's tables refuses, and leaves them as they were") {

    /**
     * Setup:
     *
     * The test database emptied of every table, then given a table of another application's, with one row, and no `system` table:
     * a database URL that names another application's database. Then the migration a start runs. The database is reset afterwards
     * for the rest of the suite.
     *
     * Assertions:
     *
     * The migration refuses the database, naming its table and saying it holds no Altitude schema. The table and its row are
     * still there, and nothing of Altitude's schema was created beside them.
     */
    TestDatabase.wipe(testApp)

    try {
      testApp.txManager.withTransaction {
        update("CREATE TABLE invoice (id INT PRIMARY KEY, total INT NOT NULL)")
        update("INSERT INTO invoice (id, total) VALUES (1, 4200)")
      }

      val refusal = intercept[IllegalStateException](testApp.migrationService.migrate())
      refusal.getMessage should include("invoice")
      refusal.getMessage should include("no Altitude schema")

      tablesInSchema shouldBe List("invoice")
      testApp.txManager.asReadOnly(query("SELECT total FROM invoice WHERE id = 1")).map(_("total").toString) shouldBe
        List("4200")
    } finally {
      TestDatabase.reset(testApp)
    }
  }

  test("A fresh database whose schema cannot be stamped with its version is left empty") {

    /**
     * Setup:
     *
     * The test database emptied of every table, then migrated with a schema script whose `system` table has no version column:
     * the script runs, and stamping the version fails. On SQLite the script also creates an FTS5 table and a trigger, on
     * PostgreSQL the vector extension and a column of its type. The database is reset afterwards for the rest of the suite.
     *
     * Assertions:
     *
     * The migration fails with the stamp's error, and the database holds none of the script's tables: the script and the stamp
     * are one transaction, so the next start finds a fresh database again rather than a schema with no version.
     */
    val version = testApp.service.system.readMetadata.version
    TestDatabase.wipe(testApp)

    try {
      val unstamped =
        MigrationService(testApp, testApp.txManager, version, s"/schema-scripts/unstamped/${testApp.dataSourceType}")
      intercept[SQLException](unstamped.migrate())

      tablesInSchema shouldBe List()
    } finally {
      TestDatabase.reset(testApp)
    }
  }

  test("A fresh database whose schema seeds no system row fails its version stamp and is left empty") {

    /**
     * Setup:
     *
     * A SQLite database of its own, in a new file with a transaction manager of its own, migrated with a schema script whose
     * `system` table holds no row, so the version stamp writes none.
     *
     * Assertions:
     *
     * The migration fails, and the database holds none of the script's tables: a schema stamped with no version would be taken
     * for a fresh database at the next start, and the schema script run over it.
     *
     * Edge cases:
     *
     * On a database of its own, since the bundle's is shared by every suite after this one; one engine shows it, the stamp's
     * check being the same on both.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.SQLITE)
    val testDir = Files.createDirectories(java.nio.file.Paths.get(testApp.config.getString(Const.Conf.TEST_DIR)).toAbsolutePath)
    val dbFile = Files.createTempFile(testDir, "unrowed", ".sqlite.db")
    val manager = new TransactionManager(
      testApp.config.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(s"jdbc:sqlite:$dbFile")),
      None,
      () => None)

    try {
      val unrowed = MigrationService(testApp, manager, 1, "/schema-scripts/unrowed/sqlite")
      intercept[IllegalStateException](unrowed.migrate())

      manager.asReadOnly {
        query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('system', 'note')")
      } shouldBe empty
    } finally {
      manager.shutdown()
      List("", "-wal", "-shm").foreach(suffix => Files.deleteIfExists(java.nio.file.Paths.get(s"$dbFile$suffix")))
    }
  }

  test("A database behind the code's version with no script to bring it forward stops the migration, naming the script") {

    /**
     * Setup:
     *
     * The test database at the current schema version, and a migration to the version after it from the schema scripts, which
     * hold no script for that version.
     *
     * Assertions:
     *
     * The migration fails naming the missing script and the two versions, and the database keeps its version.
     */
    val version = testApp.service.system.readMetadata.version
    val ahead = MigrationService(testApp, testApp.txManager, version + 1, s"/migrations/${testApp.dataSourceType}")

    val failure = intercept[IllegalStateException](ahead.migrate())
    failure.getMessage should include(s"/migrations/${testApp.dataSourceType}/${version + 1}.sql")
    failure.getMessage should include(s"version $version")

    testApp.service.system.readMetadata.version shouldBe version
  }

  test("Every script an upgrade needs is read before the first runs, so a missing one changes nothing") {

    /**
     * Setup:
     *
     * The test database at the current schema version, and a migration two versions ahead from schema scripts that hold the
     * script of the first of them, which creates a table, and none for the second.
     *
     * Assertions:
     *
     * The migration fails naming the missing second script, the database keeps its version, and the first script's table was
     * never created.
     */
    val version = testApp.service.system.readMetadata.version
    // The resource holds 3.sql, the script after the current version
    testApp.schemaVersion shouldBe 2
    val twoAhead =
      MigrationService(testApp, testApp.txManager, version + 2, s"/schema-scripts/one-ahead/${testApp.dataSourceType}")

    val failure = intercept[IllegalStateException](twoAhead.migrate())
    failure.getMessage should include(s"/schema-scripts/one-ahead/${testApp.dataSourceType}/${version + 2}.sql")

    testApp.service.system.readMetadata.version shouldBe version
    tablesInSchema should not contain "migrated_ahead"
  }

  test("A fresh PostgreSQL database whose schema holds an extension's views gets the schema beside them") {

    /**
     * Setup:
     *
     * On PostgreSQL alone: the test database emptied of every table, then given `pg_stat_statements`, an extension whose views it
     * keeps in `public`, as a database whose administrator installed one before the first start. Then the migration a start runs.
     * The database is reset afterwards for the rest of the suite, which drops the extension with `public`.
     *
     * Assertions:
     *
     * The migration creates the schema at the current version: an extension's own relations are no other application's tables.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val version = testApp.service.system.readMetadata.version
    TestDatabase.wipe(testApp)

    try {
      testApp.txManager.withTransaction(update("CREATE EXTENSION pg_stat_statements"))

      testApp.migrationService.migrate()

      testApp.service.system.readMetadata.version shouldBe version
    } finally {
      TestDatabase.reset(testApp)
    }
  }

  test("A start whose PostgreSQL search path names no schema that exists refuses, naming the search path") {

    /**
     * Setup:
     *
     * On PostgreSQL alone: the test database as the suite left it, and the migration a start runs over connections whose search
     * path names only a schema that does not exist, so they see no `system` table and have no schema to create one in.
     *
     * Assertions:
     *
     * The migration refuses, saying no schema of the search path exists, and the database keeps its schema and version.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val version = testApp.service.system.readMetadata.version

    withSearchPath("altitude_nowhere") {
      manager =>
        val refusal =
          intercept[IllegalStateException](MigrationService(testApp, manager, version, "/migrations/postgres").migrate())
        refusal.getMessage should include("search path")
    }

    testApp.service.system.readMetadata.version shouldBe version
  }

  test("A start whose PostgreSQL search path leads past Altitude's schema refuses, naming the schema that holds it") {

    /**
     * Setup:
     *
     * On PostgreSQL alone: the test database as the suite left it, with Altitude's schema in `public`, and an empty schema of
     * another name. Then the migration a start runs over connections whose search path names only the empty schema, as a role
     * whose search path was changed after the first start.
     *
     * Assertions:
     *
     * The migration refuses, naming `public` as the schema that holds Altitude's `system` table, and creates nothing in the empty
     * schema.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val version = testApp.service.system.readMetadata.version
    testApp.txManager.withTransaction(update("CREATE SCHEMA altitude_elsewhere"))

    try {
      withSearchPath("altitude_elsewhere") {
        manager =>
          val refusal =
            intercept[IllegalStateException](MigrationService(testApp, manager, version, "/migrations/postgres").migrate())
          refusal.getMessage should include("public")
      }

      testApp.txManager.asReadOnly {
        query(
          "SELECT COUNT(*) AS n FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?",
          "altitude_elsewhere").head("n").toString
      } shouldBe "0"
    } finally {
      testApp.txManager.withTransaction(update("DROP SCHEMA altitude_elsewhere CASCADE"))
    }
  }

  test("A saved import.parallelism sizes the import dispatcher of the next instance, not the running one") {

    /**
     * Setup:
     *
     * The running instance's import thread count, then the import parallelism saved as 3 and a second instance started after the
     * save.
     *
     * Assertions:
     *
     * The running instance keeps its thread count. The new instance sizes its import dispatcher at 3 from its startup Settings,
     * and gives the Setting as the reason.
     */
    val running = testApp.importParallelism

    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "3"))

    testApp.importParallelism shouldBe running
    withFreshInstance {
      other =>
        other.importParallelism shouldBe 3
        other.importParallelism shouldBe other.settings.atStartup.importParallelism
        other.importParallelismResolution.value shouldBe 3
        other.importParallelismResolution.reason shouldBe "the Setting"
    }
  }

  test("A failure to read the schema version other than a missing table stops the migration") {

    /**
     * Setup:
     *
     * On PostgreSQL alone: a read statement timeout of one second, and another connection holding the `system` table under an
     * exclusive lock.
     *
     * Assertions:
     *
     * The migration's read of the schema version is cancelled at the time limit, and the migration fails with that cancellation
     * rather than taking the database for a fresh one and running the whole schema script over it.
     */
    // Only PostgreSQL cancels a read at its time limit; SQLite has no comparable failure to stage
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)

    // Another transaction holds the table locked, so the version read runs past a one-second time limit and is cancelled
    withSetting(Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT, "1") {
      val locking = testApp.txManager.connection(readOnly = false)
      try {
        val stmt = locking.createStatement()
        try stmt.execute("LOCK TABLE system IN ACCESS EXCLUSIVE MODE")
        finally stmt.close()

        // Taken for a fresh database, the migration would run all.sql over the existing schema
        intercept[SQLException](testApp.migrationService.migrate()).getSQLState shouldBe "57014"
      } finally {
        locking.rollback()
        locking.close()
      }
    }
  }
}
