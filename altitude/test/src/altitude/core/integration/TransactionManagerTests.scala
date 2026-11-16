package altitude.core.integration

import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ empty, shouldBe }
import org.sqlite.SQLiteConfig
import scalasql.core.SqlStr.SqlStringSyntax

import scala.io.Source

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.QueryTimeoutException
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.models.Settings
import altitude.core.transactions.TransactionManager

/**
 * What a transaction is on each engine: a read sees one snapshot, a SQLite write holds the write lock from its start, every
 * connection is opened with the engine's settings, and a PostgreSQL read statement has a time limit.
 */
@DoNotDiscover class TransactionManagerTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val isSqlite = testApp.dataSourceType == Const.DbEngineName.SQLITE

  private def countAssets: Int = query("SELECT count(*) AS n FROM asset").head("n").toString.toInt

  test("A read transaction reads one snapshot, whatever another transaction commits meanwhile") {

    /**
     * Setup:
     *
     * One asset, then a read transaction that counts the assets, has another thread commit a second asset, and counts again.
     *
     * Assertions:
     *
     * Both counts inside the read transaction agree; a read transaction started afterwards sees the new asset.
     */
    testContext.persistAsset()

    val (before, after) = testApp.txManager.asReadOnly {
      val before = countAssets
      val (thread, failure) = onAnotherThread(testContext.persistAsset(): Unit)
      thread.join()
      failure.get.foreach(throw _)
      (before, countAssets)
    }

    after shouldBe before
    testApp.txManager.asReadOnly(countAssets) shouldBe before + 1
  }

  test("A transaction that cannot roll back fails with its own failure, and the next one runs") {

    /**
     * Setup:
     *
     * A write transaction that closes its own connection, so its rollback fails, and then throws.
     *
     * Assertions:
     *
     * The caller gets the transaction's own failure rather than the rollback's, no connection is left in the request context, and
     * the next transaction runs normally.
     */
    intercept[IllegalStateException] {
      testApp.txManager.withTransaction {
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
     * The thread finds no connection: it does not inherit the transaction it was started in.
     */
    var inherited: Option[Connection] = None

    testApp.txManager.asReadOnly {
      val thread = new Thread(() => inherited = RequestContext.conn.value)
      thread.start()
      thread.join()
    }

    inherited shouldBe None
  }

  test("An after-commit action runs once the outermost write commits, and never when it rolls back") {

    /**
     * Setup:
     *
     * An action registered in a nested write transaction, one registered in a write transaction that then fails, and one
     * registered outside any transaction.
     *
     * Assertions:
     *
     * The first action runs once, after the outermost transaction has committed, with no connection in the request context. The
     * second never runs. The third runs at once.
     *
     * Edge cases:
     *
     * An action that throws is logged and swallowed: the actions after it run, and the committed transaction does not fail.
     */
    val ran = scala.collection.mutable.ListBuffer[String]()

    testApp.txManager.withTransaction {
      testApp.txManager.withTransaction {
        testApp.txManager.afterCommit(throw IllegalStateException("A failing action"))
        testApp.txManager.afterCommit(ran += s"committed, connection ${RequestContext.conn.value.isDefined}")
      }
      ran shouldBe empty
    }
    ran.toList shouldBe List("committed, connection false")

    intercept[IllegalStateException] {
      testApp.txManager.withTransaction {
        testApp.txManager.afterCommit(ran += "rolled back")
        throw IllegalStateException("The change failed")
      }
    }
    ran.toList shouldBe List("committed, connection false")

    testApp.txManager.afterCommit(ran += "no transaction")
    ran.toList shouldBe List("committed, connection false", "no transaction")
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
       * A second SQLite connection with no busy timeout, so it is refused when it cannot take the write lock at once, and a write
       * transaction that has only read.
       *
       * Assertions:
       *
       * While the write transaction is open, the other connection cannot begin a write transaction of its own.
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
       * The journal mode, foreign key and temporary storage settings, read in a read and in a write transaction.
       *
       * Assertions:
       *
       * Both kinds of connection have WAL, foreign keys on and temporary storage in memory.
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
       * The query_only setting, read in a read and in a write transaction.
       *
       * Assertions:
       *
       * Only the read connection is query-only, so a write cannot slip into a read transaction and be silently rolled back.
       */
      testApp.txManager.asReadOnly(query("PRAGMA query_only").head("query_only").toString) shouldBe "1"
      testApp.txManager.withTransaction(query("PRAGMA query_only").head("query_only").toString) shouldBe "0"
    }

    test("A SQLite connection opened before the schema existed searches the vectors once the schema is created") {

      /**
       * Setup:
       *
       * A transaction manager of its own over a new, empty database file, its read and write connections opened before any table
       * exists, as a fresh install's are. The schema is then created on the write connection and the manager told so.
       *
       * Assertions:
       *
       * Plain read and write transactions search the face vectors and the SigLIP 2 Scene embeddings: the connections opened
       * before the `face` and `asset` tables existed have been replaced by ones set up for both.
       */
      val dbFile = new File(testApp.config.getString(Const.Conf.TEST_DIR), "fresh.sqlite.db")
      dbFile.getParentFile.mkdirs()
      List("", "-wal", "-shm").foreach(suffix => new File(dbFile.getPath + suffix).delete())
      val config =
        testApp.config.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(s"jdbc:sqlite:${dbFile.getPath}"))
      val manager = new TransactionManager(config, None, () => None)

      // Nothing to search yet, but each scan has to find its column registered
      def noVector(dimensions: Int): String = "[" + List.fill(dimensions)("0.0").mkString(",") + "]"
      def searchCount: Int =
        List(("face", "features", 512), ("asset", "siglip2_embedding", 768)).map {
          case (table, column, dimensions) =>
            query(s"SELECT count(*) AS n FROM vector_full_scan('$table', '$column', vector_as_f32(?))", noVector(dimensions))
              .head("n")
              .toString
              .toInt
        }.sum

      try {
        manager.asReadOnly(())
        manager.withTransaction {
          val schema = Source.fromURL(getClass.getResource("/migrations/sqlite/all.sql")).mkString
          val statement = RequestContext.getConn.createStatement()
          try statement.executeUpdate(schema)
          finally statement.close()
        }
        manager.schemaCreated()

        manager.asReadOnly(searchCount) shouldBe 0
        manager.withTransaction(searchCount) shouldBe 0
      } finally manager.shutdown()
    }
  } else {
    test(
      "A PostgreSQL read transaction plans without JIT and with custom plans, and a statement past its time limit is refused") {

      /**
       * Setup:
       *
       * A read transaction that reads the JIT and plan cache settings, then, with the read statement timeout at one second, a
       * read statement that sleeps for two.
       *
       * Assertions:
       *
       * JIT is off and plans are custom, and the statement past the time limit fails with a query timeout.
       */
      import AltitudePostgresDialect.*

      testApp.txManager.asReadOnly {
        List("jit", "plan_cache_mode").map(name => query(s"SELECT current_setting('$name') AS v").head("v").toString) shouldBe
          List("off", "force_custom_plan")
      }

      withSetting(Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT, "1") {
        intercept[QueryTimeoutException] {
          testApp.txManager.asReadOnly(Db.read(AltitudePostgresDialect)(_.runSql[Int](sql"SELECT 1 FROM pg_sleep(2)")))
        }
      }
    }

    test("A PostgreSQL vector search keeps its limit of candidates, up to pgvector's cap, and scans on until the limit is met") {

      /**
       * Setup:
       *
       * pgvector's four index scan settings, read in face vector transactions (write and read-only) and in vector searches for
       * 400, 5,000 and 10 rows, with the search width Setting at its default of 100 and then at 150.
       *
       * Assertions:
       *
       * A face vector transaction keeps the Setting's candidates and scans in relaxed order under pgvector's own caps. A vector
       * search is read-only, keeps as many candidates as its limit, lifts the cap on scanned tuples and raises the memory one, so
       * the scan ends only when the limit is met.
       *
       * Edge cases:
       *
       * pgvector caps candidates at 1,000, so a search for 5,000 keeps 1,000; a search for fewer rows than the Setting keeps the
       * Setting's. A face vector transaction nested in a vector search runs under the search's setup, and a vector search nested
       * in a face vector transaction raises the setup to its own.
       */
      def scanSettings: List[String] = {
        // pgvector's settings are known to a session once it has used the extension
        query("SELECT '[1]'::vector AS loaded")
        List("ef_search", "iterative_scan", "max_scan_tuples", "scan_mem_multiplier").map(
          name => query(s"SELECT current_setting('hnsw.$name') AS v").head("v").toString)
      }
      def faces(candidates: Int): List[String] = List(candidates.toString, "relaxed_order", "20000", "1")
      def search(candidates: Int): List[String] = List(candidates.toString, "relaxed_order", "2147483647", "8")

      testApp.txManager.withFaceVector(scanSettings) shouldBe faces(100)
      testApp.txManager.withFaceVectorReadOnly(scanSettings) shouldBe faces(100)
      testApp.txManager.withVectorSearch(limit = 400)(scanSettings) shouldBe search(400)
      testApp.txManager.withVectorSearch(limit = 5000)(scanSettings) shouldBe search(1000)
      testApp.txManager.withVectorSearch(limit = 10)(scanSettings) shouldBe search(100)
      testApp.txManager.withVectorSearch(limit = 10) {
        query("SELECT current_setting('transaction_read_only') AS v").head("v")
      } shouldBe "on"

      withSetting(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH, "150") {
        testApp.txManager.withFaceVector(scanSettings) shouldBe faces(150)
        testApp.txManager.withVectorSearch(limit = 10)(scanSettings) shouldBe search(150)
      }

      testApp.txManager.withVectorSearch(limit = 400) {
        testApp.txManager.withFaceVectorReadOnly(scanSettings) shouldBe search(400)
      }
      testApp.txManager.withFaceVector {
        scanSettings shouldBe faces(100)
        testApp.txManager.withVectorSearch(limit = 400)(scanSettings) shouldBe search(400)
      }
    }
  }

  test("A vector search is a read transaction that a search nested in it joins") {

    /**
     * Setup:
     *
     * A vector search with a second vector search and a plain read transaction nested in it, each reading its connection.
     *
     * Assertions:
     *
     * The three run on one connection, which refuses writes, and none is left behind once the outermost ends.
     */
    def refusesWrites: Boolean =
      if (isSqlite) query("PRAGMA query_only").head("query_only").toString == "1"
      else query("SELECT current_setting('transaction_read_only') AS v").head("v") == "on"

    val connections = testApp.txManager.withVectorSearch(limit = 10) {
      refusesWrites shouldBe true
      List(
        RequestContext.getConn,
        testApp.txManager.withVectorSearch(limit = 20)(RequestContext.getConn),
        testApp.txManager.asReadOnly(RequestContext.getConn))
    }
    connections.distinct.size shouldBe 1
    RequestContext.conn.value shouldBe None
  }
}
