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

    /**
     * Setup:
     *
     * One asset, then a read transaction that counts the assets, has another thread persist and commit a second asset, and
     * counts again.
     *
     * Assertions:
     *
     * Both counts inside the read transaction agree, while a read transaction started afterwards sees the new asset.
     */
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

    /**
     * Setup:
     *
     * A write transaction that closes its own connection, so that its rollback fails, and then throws.
     *
     * Assertions:
     *
     * The caller gets the transaction's own failure rather than the rollback's, no connection is left in the request context,
     * and the next transaction runs normally.
     */
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

    /**
     * Setup:
     *
     * A read transaction that starts a thread and records the connection the thread finds in the request context.
     *
     * Assertions:
     *
     * The thread finds no connection - a thread does not inherit the transaction it was started in.
     */
    var inherited: Option[Connection] = None

    testApp.txManager.asReadOnly {
      val thread = new Thread(() => inherited = RequestContext.conn.value)
      thread.start()
      thread.join()
    }

    inherited shouldBe None
  }

  test("A transaction leaves no connection behind, committed or failed") {

    /**
     * Setup:
     *
     * A write transaction that commits, then a read transaction that throws.
     *
     * Assertions:
     *
     * Neither leaves a connection in the request context once it ends.
     */
    testApp.txManager.withTransaction(countAssets)
    RequestContext.conn.value shouldBe None

    intercept[IllegalStateException](testApp.txManager.asReadOnly(throw IllegalStateException()))
    RequestContext.conn.value shouldBe None
  }

  if (isSqlite) {
    test("A SQLite write transaction holds the write lock from its start, even when it has only read") {

      /**
       * Setup:
       *
       * A second SQLite connection with no busy timeout, so that it is refused when it cannot take the write lock at once, and a
       * write transaction that has only read.
       *
       * Assertions:
       *
       * While the write transaction is open, the other connection cannot begin a write transaction of its own: the write lock is
       * taken when the transaction starts, not at its first write.
       */
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

      /**
       * Setup:
       *
       * The journal mode, foreign key and temporary storage settings, read in a read transaction and in a write transaction.
       *
       * Assertions:
       *
       * Both kinds of connection are opened with the same settings: WAL, foreign keys on and temporary storage in memory.
       */
      def settings: List[String] =
        List("journal_mode", "foreign_keys", "temp_store").map(name => query(s"PRAGMA $name").head(name).toString)

      testApp.txManager.asReadOnly(settings) shouldBe List("wal", "1", "2")
      testApp.txManager.withTransaction(settings) shouldBe List("wal", "1", "2")
    }

    test("A SQLite read connection refuses writes, which its closing rollback would otherwise discard") {

      /**
       * Setup:
       *
       * The query_only setting, read in a read transaction and in a write transaction.
       *
       * Assertions:
       *
       * Only the read connection is query-only, so a write cannot slip into a read transaction and be silently rolled back.
       */
      testApp.txManager.asReadOnly(query("PRAGMA query_only").head("query_only").toString) shouldBe "1"
      testApp.txManager.withTransaction(query("PRAGMA query_only").head("query_only").toString) shouldBe "0"
    }
  } else {
    test(
      "A PostgreSQL read transaction plans without JIT and with custom plans, and a statement past its time limit is refused") {

      /**
       * Setup:
       *
       * A read transaction that reads the JIT and plan cache settings, then a read statement that sleeps for three seconds.
       *
       * Assertions:
       *
       * A read transaction plans without JIT and with custom plans, and a statement that runs past the time limit is refused
       * with a query timeout.
       */
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
