package altitude.core.integration

import altitude.test.TestFocus
import java.sql.SQLException
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.models.Settings
import altitude.core.suites.TestDatabase

/**
 * What a server starting against this test database does: creates the schema of a fresh database, migrates only when behind,
 * loads the Settings, and sizes the import dispatcher from `import.parallelism` as it was saved, which is the Restart semantics.
 */
@DoNotDiscover class StartupTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  override def afterEach(): Unit = {
    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "0"))
    super.afterEach()
  }

  /** A second instance against the test database, shut down afterwards; nothing else may run against it meanwhile */
  private def withFreshInstance[T](f: Altitude => T): T = {
    val other = new Altitude(dbEngineOverride = Some(testApp.dataSourceType))
    try f(other)
    finally {
      other.cleanup()
      other.actorSystem.terminate()
      Await.ready(other.actorSystem.whenTerminated, 10.seconds)
    }
  }

  test("A new instance against a migrated database keeps the schema version and starts with the Settings loaded") {
    val versionBefore = testApp.service.system.readMetadata.version

    withFreshInstance {
      other =>
        other.service.system.readMetadata.version shouldBe versionBefore
        other.settings.atStartup.importParallelism shouldBe 0
        other.importParallelism should be >= 2
    }
  }

  test("A new instance against a fresh database creates the schema and seeds the Settings") {
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
    val running = testApp.importParallelism

    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "3"))

    testApp.importParallelism shouldBe running
    withFreshInstance {
      other =>
        other.importParallelism shouldBe 3
        other.importParallelism shouldBe other.settings.atStartup.importParallelism
    }
  }

  test("A failure to read the schema version other than a missing table stops the migration") {
    // Only PostgreSQL cancels a read at its time limit (2 s under test); SQLite has no comparable failure to stage
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)

    // Another transaction holds the table locked, so the version read runs past the time limit and is cancelled
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
