package altitude.core.integration

import altitude.test.LoggedLine
import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ a, be, empty, have, include, not, should, shouldBe }
import org.scalatest.tagobjects.Slow
import org.sqlite.SQLiteConfig
import scalasql.core.SqlStr.SqlStringSyntax

import scala.concurrent.duration.DurationLong
import scala.io.Source

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.NativeLibraries
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
     * Outside a transaction, too, a throwing action does not reach its caller.
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

    testApp.txManager.afterCommit(throw IllegalStateException("A failing action outside a transaction"))
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
    assume(isSqlite)
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

  /**
   * Runs `second` on this thread while another holds `manager`'s write connection: for `holdFor` milliseconds, or with none until
   * `second` has ended. Answers how long `second` took, and rethrows a failure of the holder.
   */
  private def whileWriting[A](manager: TransactionManager, holdFor: Option[Long])(second: => A): (Either[Throwable, A], Long) = {
    val holding = new CountDownLatch(1)
    val secondEnded = new CountDownLatch(1)
    val (holder, failure) = onAnotherThread(manager.withTransaction {
      holding.countDown()
      holdFor.fold(holdUntil(secondEnded))(Thread.sleep)
    })
    holding.await(10, TimeUnit.SECONDS) shouldBe true
    val started = System.nanoTime
    val result =
      try Right(second)
      catch { case e: Exception => Left(e) }
      finally secondEnded.countDown()
    val waited = (System.nanoTime - started).nanos.toMillis
    joined(holder)
    failure.get.foreach(throw _)
    (result, waited)
  }

  test("A SQLite writer waits for the write connection past HikariCP's 30 seconds", Slow) {

    /**
     * Setup:
     *
     * A write transaction held open for 35 seconds on another thread, and a second write transaction begun while it holds the
     * write connection, with the transaction manager's WARN lines captured.
     *
     * Assertions:
     *
     * The second transaction runs once the first ends, after waiting more than 30 seconds, rather than failing for want of a
     * connection: the write pool waits as long as `db.sqlite.write_connection_timeout`, minutes by default. A WARN says how long
     * it waited.
     */
    assume(isSqlite)
    var outcome: (Either[Throwable, Int], Long) = (Left(IllegalStateException("not run")), 0L)
    val lines = LoggedLine.during {
      outcome = whileWriting(testApp.txManager, holdFor = Some(35000))(testApp.txManager.withTransaction(countAssets))
    }

    val (second, waited) = outcome
    second.isRight shouldBe true
    waited should be > 30000L
    lines.map(_.message).filter(_.startsWith("Waited")) should have size 1
  }

  test("A SQLite writer waits for the write connection as long as the configured timeout, and no longer") {

    /**
     * Setup:
     *
     * A transaction manager of its own over a new database file, whose write connection timeout is one second, and a write
     * transaction held open on another thread until a second one, begun while the first holds the write connection, has ended.
     *
     * Assertions:
     *
     * The second transaction fails for want of a connection after waiting the second, while the first still holds it, and long
     * before HikariCP's own 30 seconds.
     */
    assume(isSqlite)
    val testDir = Files.createDirectories(Paths.get(testApp.config.getString(Const.Conf.TEST_DIR)).toAbsolutePath)
    val dbFile = Files.createTempFile(testDir, "timeout", ".sqlite.db")
    val config = testApp.config
      .withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(s"jdbc:sqlite:$dbFile"))
      .withValue(Const.Conf.SQLITE_WRITE_CONNECTION_TIMEOUT, ConfigValueFactory.fromAnyRef("1s"))
    val manager = new TransactionManager(config, None, () => None)

    try {
      val (second, waited) = whileWriting(manager, holdFor = None)(manager.withTransaction(()))
      second.left.toOption.get shouldBe a[SQLTransientConnectionException]
      waited should be >= 1000L
      waited should be < 30000L
    } finally {
      manager.shutdown()
      List("", "-wal", "-shm").foreach(suffix => Files.deleteIfExists(Paths.get(s"$dbFile$suffix")))
    }
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
    assume(isSqlite)
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
    assume(isSqlite)
    testApp.txManager.asReadOnly(query("PRAGMA query_only").head("query_only").toString) shouldBe "1"
    testApp.txManager.withTransaction(query("PRAGMA query_only").head("query_only").toString) shouldBe "0"
  }

  test("A SQLite connection opened before the schema existed searches the vectors once the schema is created") {

    /**
     * Setup:
     *
     * A transaction manager of its own over a new, empty database file, its read and write connections opened before any table
     * exists, as a fresh install's are. The schema is then created on the write connection; the connections stay in their pools.
     *
     * Assertions:
     *
     * Plain read and write transactions search the face vectors and the SigLIP 2 Scene embeddings: the connections opened before
     * the `face` and `asset` tables existed register both columns at their first transaction once the tables exist, so no
     * connection, however it raced the schema's creation, is left unable to scan them.
     */
    assume(isSqlite)
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

      manager.asReadOnly(searchCount) shouldBe 0
      manager.withTransaction(searchCount) shouldBe 0
    } finally manager.shutdown()
  }

  test("A SQLite read connection opens while another connection holds the write lock, and writes no statistics") {

    /**
     * Setup:
     *
     * A new database file holding a table of 5,000 rows with an index and no statistics, which `PRAGMA optimize` would analyze,
     * and the vector extension's table, and a connection of its own holding the file's write lock in an open transaction, as
     * another process might. A transaction manager of its own over the file then runs a read.
     *
     * Assertions:
     *
     * The read answers at once, well within the 10 seconds a connection waits on a lock: no read connection refreshes the
     * statistics, which would write the file outside the one write connection, and the write connection's own refresh at open
     * gives up on a locked file rather than wait. Once the lock is let go, no read connection has written statistics.
     */
    assume(isSqlite)
    val testDir = Files.createDirectories(Paths.get(testApp.config.getString(Const.Conf.TEST_DIR)).toAbsolutePath)
    val dbFile = Files.createTempFile(testDir, "stats", ".sqlite.db")
    val url = s"jdbc:sqlite:$dbFile"
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL)
    sqliteConfig.enableLoadExtension(true)
    def execute(conn: Connection, sql: String): Unit = {
      val statement = conn.createStatement()
      try statement.execute(sql): Unit
      finally statement.close()
    }

    val holder = sqliteConfig.createConnection(url)
    try {
      // The extension's first load into a file writes its own table, which a later load finds
      val loading = holder.prepareStatement("SELECT load_extension(?)")
      try {
        loading.setString(1, TransactionManager.vectorExtensionPath(None))
        loading.execute(): Unit
      } finally loading.close()
      execute(holder, "CREATE TABLE numbers (n INTEGER, flag INTEGER NOT NULL)")
      execute(
        holder,
        "WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 5000) INSERT INTO numbers SELECT i % 50, i % 2 FROM s")
      execute(holder, "CREATE INDEX numbers_1 ON numbers (n)")
      holder.setAutoCommit(false)
      execute(holder, "INSERT INTO numbers VALUES (1, 1)")

      val config = testApp.config.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(url))
      val manager = new TransactionManager(config, None, () => None)
      try {
        val started = System.nanoTime
        manager.asReadOnly(query("SELECT count(*) AS n FROM numbers").head("n").toString) shouldBe "5000"
        (System.nanoTime - started) / 1000000 should be < 5000L
        holder.rollback()
        manager
          .asReadOnly(query("SELECT count(*) AS n FROM sqlite_master WHERE name = 'sqlite_stat1'"))
          .head("n")
          .toString shouldBe "0"
      } finally manager.shutdown()
    } finally {
      holder.close()
      List("", "-wal", "-shm").foreach(suffix => Files.deleteIfExists(Paths.get(s"$dbFile$suffix")))
    }
  }

  test("sqlite-vector loads from the folder altitude.native.dir names, and from the classpath when it names none") {

    /**
     * Setup:
     *
     * A folder holding a copy of this platform's sqlite-vector build, and an empty folder. A transaction manager of its own over
     * a new database file is built three times: with the `altitude.native.dir` property unset, naming the first folder, and
     * naming the empty one.
     *
     * Assertions:
     *
     * Unset, the extension is the build on the classpath; set, it is the file in the folder. A connection of either manager
     * answers with the extension's version.
     *
     * Edge cases:
     *
     * The folder that holds no build fails the connection, naming the property and the missing file: with the property set,
     * nothing is taken from the classpath. An empty property counts as unset, and a relative folder is under the working
     * directory. A folder named `o'brien`, as a home folder such as `C:\Users\O'Brien` is, loads the build: the path is bound,
     * never spliced into the statement. A build on the classpath inside a jar, as in one that is no platform jar, fails naming
     * the jar, the property and the platform jar: SQLite loads an extension from a file alone, and nothing extracts it.
     */
    assume(isSqlite)
    val testDir = Paths.get(testApp.config.getString(Const.Conf.TEST_DIR)).toAbsolutePath
    Files.createDirectories(testDir)
    val onClasspath = Paths.get(TransactionManager.vectorExtensionPath(None))
    val nativeDir = Files.createTempDirectory(testDir, "native")
    val emptyDir = Files.createTempDirectory(testDir, "native-empty")
    val inFolder = nativeDir.resolve(onClasspath.getFileName)
    Files.copy(onClasspath, inFolder)

    // The version a connection of a new manager answers with, built while the property has the value
    def versionWith(nativeDir: Option[String]): String = {
      val property = TransactionManager.NATIVE_DIR_PROPERTY
      // Put back afterwards, should the test run itself have been started with one
      val before = Option(System.getProperty(property))
      nativeDir.fold(System.clearProperty(property))(System.setProperty(property, _))
      val dbFile = Files.createTempFile(testDir, "native", ".sqlite.db")
      val config = testApp.config.withValue(Const.Conf.SQLITE_URL, ConfigValueFactory.fromAnyRef(s"jdbc:sqlite:$dbFile"))
      val manager = new TransactionManager(config, None, () => None)
      try manager.asReadOnly(query("SELECT vector_version() AS version").head("version").toString)
      finally {
        manager.shutdown()
        before.fold(System.clearProperty(property))(System.setProperty(property, _))
      }
    }

    // The classpath holds a folder per platform under sqlite-vector/
    onClasspath.getParent.getParent.getFileName.toString shouldBe "sqlite-vector"
    Files.isRegularFile(onClasspath) shouldBe true
    TransactionManager.vectorExtensionPath(Some(nativeDir.toString)) shouldBe inFolder.toString
    val relativeDir = Paths.get("").toAbsolutePath.relativize(nativeDir)
    TransactionManager.vectorExtensionPath(Some(relativeDir.toString)) shouldBe inFolder.toString
    TransactionManager.vectorExtensionPath(Some("")) shouldBe onClasspath.toString

    val version = versionWith(None)
    version should not be empty
    versionWith(Some(nativeDir.toString)) shouldBe version

    val quotedDir = Files.createDirectories(Files.createTempDirectory(testDir, "native").resolve("o'brien"))
    Files.copy(onClasspath, quotedDir.resolve(onClasspath.getFileName))
    versionWith(Some(quotedDir.toString)) shouldBe version

    val refusal = intercept[Exception](versionWith(Some(emptyDir.toString)))
    val reasons = Iterator.iterate[Throwable](refusal)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" / ")
    reasons should include(TransactionManager.NATIVE_DIR_PROPERTY)
    reasons should include(emptyDir.resolve(onClasspath.getFileName).toString)

    val inJar =
      new java.net.URI(s"jar:file:/opt/altitude/altitude.jar!/sqlite-vector/linux-x86/${onClasspath.getFileName}").toURL
    val inside = intercept[IllegalStateException](TransactionManager.vectorExtensionPath(None, _ => inJar))
    inside.getMessage should include("/opt/altitude/altitude.jar")
    inside.getMessage should include(TransactionManager.NATIVE_DIR_PROPERTY)
    inside.getMessage should include(NativeLibraries.BUILD)
  }
  test("A PostgreSQL read transaction plans without JIT and with custom plans, and a statement past its time limit is refused") {

    /**
     * Setup:
     *
     * A read transaction that reads the JIT and plan cache settings, then, with the read statement timeout at one second, a read
     * statement that sleeps for two.
     *
     * Assertions:
     *
     * JIT is off and plans are custom, and the statement past the time limit fails with a query timeout.
     */
    assume(!isSqlite)
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

  test("A PostgreSQL read transaction takes the largest read statement timeout the Setting allows") {

    /**
     * Setup:
     *
     * The read statement timeout saved at its most, 2,147,483 seconds.
     *
     * Assertions:
     *
     * A read transaction begins, and its statement timeout is that many seconds: the milliseconds it is set in fit PostgreSQL's
     * limit. A guard of the Setting's bound: at the bound the product fits an `Int` too, so the test does not tell the `Long`
     * product from an `Int` one.
     */
    assume(!isSqlite)
    withSetting(Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT, "2147483") {
      testApp.txManager.asReadOnly(query("SHOW statement_timeout").head("statement_timeout")) shouldBe "2147483s"
    }
  }

  test("A PostgreSQL vector search keeps its limit of candidates, up to pgvector's cap, and scans on until the limit is met") {

    /**
     * Setup:
     *
     * pgvector's four index scan settings, read in face vector transactions (write and read-only) and in vector searches for 400,
     * 5,000 and 10 rows, with the search width Setting at its default of 100 and then at 150.
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
     * Setting's. A face vector transaction nested in a vector search runs under the search's setup, and a vector search nested in
     * a face vector transaction raises the setup to its own.
     */
    assume(!isSqlite)
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
