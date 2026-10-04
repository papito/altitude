package altitude.core.integration

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe
import org.sqlite.SQLiteConfig
import scalasql.core.SqlStr.SqlStringSyntax

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.QueryTimeoutException
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.dialects.AltitudePostgresDialect

/**
 * What a transaction is on each engine: a read sees one snapshot, a SQLite write holds the write lock from its start, every
 * connection is opened with the engine's settings, and a PostgreSQL read statement has a time limit.
 */
@DoNotDiscover class TransactionManagerTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val isSqlite = testApp.dataSourceType == Const.DbEngineName.SQLITE

  private def countAssets: Int = query("SELECT count(*) AS n FROM asset").head("n").toString.toInt

  /** Runs `f` on a thread of its own, outside the caller's transaction, and waits for it to finish */
  private def onAnotherThread(f: => Unit): Unit = {
    var failure: Option[Throwable] = None
    val thread = new Thread(
      () =>
        try f
        catch { case ex: Throwable => failure = Some(ex) })
    thread.start()
    thread.join()
    failure.foreach(throw _)
  }

  test("A read transaction reads one snapshot, whatever another transaction commits meanwhile") {
    testContext.persistAsset()

    val (before, after) = testApp.txManager.asReadOnly {
      val before = countAssets
      onAnotherThread(testContext.persistAsset())
      (before, countAssets)
    }

    after shouldBe before
    testApp.txManager.asReadOnly(countAssets) shouldBe before + 1
  }

  test("A transaction that cannot roll back fails with its own failure, and the next one runs") {
    intercept[IllegalStateException] {
      testApp.txManager.withTransaction {
        // The rollback of a closed connection fails
        RequestContext.getConn.close()
        throw IllegalStateException("The failure of the transaction")
      }
    }

    RequestContext.conn.value shouldBe None
    testApp.txManager.withTransaction(countAssets)
  }

  test("A thread started inside a transaction is outside of it") {
    var inherited: Option[Connection] = None

    testApp.txManager.asReadOnly {
      val thread = new Thread(() => inherited = RequestContext.conn.value)
      thread.start()
      thread.join()
    }

    inherited shouldBe None
  }

  test("A transaction leaves no connection behind, committed or failed") {
    testApp.txManager.withTransaction(countAssets)
    RequestContext.conn.value shouldBe None

    intercept[IllegalStateException](testApp.txManager.asReadOnly(throw IllegalStateException()))
    RequestContext.conn.value shouldBe None
  }

  if (isSqlite) {
    test("A SQLite write transaction holds the write lock from its start, even when it has only read") {
      // No busy timeout: a connection that cannot take the write lock at once is refused
      val noWaiting = new SQLiteConfig()
      noWaiting.setBusyTimeout(0)
      val other = DriverManager.getConnection(testApp.config.getString(Const.Conf.SQLITE_URL), noWaiting.toProperties)

      try
        testApp.txManager.withTransaction {
          countAssets
          intercept[SQLException](other.createStatement().execute("BEGIN IMMEDIATE"))
        }
      finally other.close()
    }

    test("SQLite read and write connections are in WAL mode, enforce foreign keys and keep temporary storage in memory") {
      def settings: List[String] =
        List("journal_mode", "foreign_keys", "temp_store").map(name => query(s"PRAGMA $name").head(name).toString)

      testApp.txManager.asReadOnly(settings) shouldBe List("wal", "1", "2")
      testApp.txManager.withTransaction(settings) shouldBe List("wal", "1", "2")
    }

    test("A SQLite read connection refuses writes, which its closing rollback would otherwise discard") {
      testApp.txManager.asReadOnly(query("PRAGMA query_only").head("query_only").toString) shouldBe "1"
      testApp.txManager.withTransaction(query("PRAGMA query_only").head("query_only").toString) shouldBe "0"
    }
  } else {
    test(
      "A PostgreSQL read transaction plans without JIT and with custom plans, and a statement past its time limit is refused") {
      import AltitudePostgresDialect.*

      testApp.txManager.asReadOnly {
        List("jit", "plan_cache_mode").map(name => query(s"SELECT current_setting('$name') AS v").head("v").toString) shouldBe
          List("off", "force_custom_plan")
      }

      // The test configuration's limit is two seconds
      intercept[QueryTimeoutException] {
        testApp.txManager.asReadOnly(Db.read(AltitudePostgresDialect)(_.runSql[Int](sql"SELECT 1 FROM pg_sleep(3)")))
      }
    }
  }
}
