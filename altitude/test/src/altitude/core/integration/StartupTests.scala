package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.SwitchableDescribeEmbedder
import altitude.test.TestAltitudeApp
import altitude.test.TestFocus
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
import altitude.core.suites.TestDatabase
import altitude.core.util.Sha256

/**
 * Startup against the test database: a fresh database gets the schema, an existing one is migrated only when behind, the Settings
 * load, and the import dispatcher is sized from `import.parallelism` as saved, so a change applies on restart.
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
     * The new instance creates the schema at the running instance's version and seeds the Settings, the import parallelism at its
     * default of 0.
     */
    val version = testApp.service.system.readMetadata.version
    TestDatabase.wipe(testApp)

    try {
      withFreshInstance {
        other =>
          other.service.system.readMetadata.version shouldBe version
          other.settings.current.importParallelism shouldBe 0
      }
    } finally {
      TestDatabase.reset(testApp)
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

        // Taken for a fresh database, the migration would run all.sql, which drops the schema, over the existing one
        intercept[SQLException](testApp.migrationService.migrate()).getSQLState shouldBe "57014"
      } finally {
        locking.rollback()
        locking.close()
      }
    }
  }
}
