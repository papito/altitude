package altitude.core.transactions

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.Statement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteDataSource

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import altitude.core.Const
import altitude.core.NativeLibraries
import altitude.core.RequestContext
import altitude.core.ThreadVariable
import altitude.core.models.SceneModel
import altitude.core.models.Settings

object TransactionManager:

  /** The most candidates pgvector lets a scan of an HNSW index keep (`hnsw.ef_search`) */
  private val HNSW_MAX_EF_SEARCH = 1000

  /** `hnsw.max_scan_tuples` at its highest: no cap on the tuples a scan visits */
  private val HNSW_UNCAPPED_SCAN_TUPLES = Int.MaxValue

  /**
   * The memory an iterative vector-search scan may hold, as a multiple of `work_mem` (`hnsw.scan_mem_multiplier`); past it the
   * scan stops. Measured on pgvector 0.8.2: a scan to the end of a million-vector, 768-dimension index takes 128-192 MB and about
   * 14 s. With 64 MB of `work_mem`, a multiplier of 1 finds a third of the rows that pass the filter, 2 two thirds, 3 or more all
   * of them. At 8 (512 MB) the read statement timeout ends such a scan long before its memory does.
   */
  private val HNSW_SCAN_MEM_MULTIPLIER = 8

  /** A wait for a pooled connection longer than this, HikariCP's own timeout, is logged */
  private val SLOW_CONNECTION_WAIT_MS = 30000L

  /**
   * The system property naming a folder that holds this platform's sqlite-vector build. The Docker image sets it to the folder
   * its native libraries are put in when the image is built, and a native jar's start to the folder it unpacks them into
   * (`NativeLibraries`).
   */
  val NATIVE_DIR_PROPERTY = "altitude.native.dir"

  /**
   * The sqlite-vector build for this platform, as a file SQLite can load: the one in `nativeDir` (the value of
   * [[NATIVE_DIR_PROPERTY]]; an empty one counts as unset), which must hold it, since nothing is then taken from the classpath;
   * without a folder, the build on the classpath, which `locate` finds. That one must be a file, as it is in a classes folder
   * (Mill's `run`, the tests): SQLite loads an extension only from a file, and a build inside a jar is refused, naming the jar. A
   * platform jar's start (`./mill altitude.platformJar`) has unpacked it and set the property; any jar without an
   * `Altitude-Build` in its manifest runs on SQLite only with the property set.
   */
  def vectorExtensionPath(nativeDir: Option[String], locate: String => URL = getClass.getResource): String =
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase

    // The one Mac build is Apple Silicon's: an Intel Mac runs the Docker image, so nothing loads a build of its own
    val (platformDir, extName) =
      if os.contains("mac") || os.contains("darwin") then ("macos-arm64", "vector.dylib")
      else if os.contains("win") then ("windows-x86", "vector.dll")
      else
        // Linux / other Unix
        val dir = if arch.contains("aarch64") || arch.contains("arm") then "linux-arm64" else "linux-x86"
        (dir, "vector.so")

    nativeDir.filter(_.nonEmpty) match
      case Some(dir) =>
        val file = Paths.get(dir).toAbsolutePath.resolve(extName)
        if !Files.isRegularFile(file) then
          throw IllegalStateException(s"$NATIVE_DIR_PROPERTY names $dir, but the sqlite-vector extension is not there: $file")
        file.toString
      case None =>
        val resource = s"/sqlite-vector/$platformDir/$extName"
        val url = Option(locate(resource)).getOrElse {
          throw IllegalStateException(s"The sqlite-vector extension is not on the classpath: $resource")
        }
        if url.getProtocol != "file" then
          throw IllegalStateException(
            s"The sqlite-vector extension is inside $url, and SQLite loads an extension only from a file. This jar's " +
              s"manifest has no ${NativeLibraries.BUILD}, so nothing unpacked it: start a native jar " +
              s"(./mill altitude.platformJar), or set $NATIVE_DIR_PROPERTY to a folder that holds $extName.")
        Paths.get(url.toURI).toString

/**
 * Connections and transactions. A transaction runs on a connection borrowed from a HikariCP pool and carried by
 * `RequestContext.conn`; one started inside another on the same thread joins it, and a thread started inside one is outside it.
 *
 * PostgreSQL has one pool. A read transaction is `REPEATABLE READ, READ ONLY` (one snapshot), limited by the read statement
 * timeout Setting; a write is the default `READ COMMITTED`.
 *
 * SQLite has a read pool and a one-connection write pool over the same file. Writes begin `IMMEDIATE`, so the single write
 * connection queues writers in the process instead of failing them with `SQLITE_BUSY`; a writer that waits longer than
 * `db.sqlite.write_connection_timeout` for it fails. Read connections refuse writes (`query_only`): a write nested in a read
 * transaction would otherwise be discarded by its closing rollback.
 *
 * Read transactions end with a rollback. An `explainer` (development only) wraps each connection in an [[ExplainingConnection]].
 * `settings` is read as each transaction begins; it is None before the first load, when a fresh database's migration runs without
 * a time limit.
 */
class TransactionManager(val config: Config, explainer: Option[SqlExplainer], settings: () => Option[Settings]):

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val engine: String = config.getString(Const.Conf.DB_ENGINE)

  // The vector search limit this thread's transaction is set up for ([[settingUpVectorSearch]]); 0 for face vectors
  private val vectorSearchLimit = new ThreadVariable[Option[Int]](None)

  // The actions `afterCommit` registered in the thread's outermost transaction, which runs them once it has committed
  private val afterCommitActions = new ThreadVariable[Option[ListBuffer[() => Unit]]](None)

  // Whether the pools were opened, so that a manager that never ran a transaction has nothing to close
  @volatile private var isOpen = false

  /**
   * The read pool and the write pool, the same one on PostgreSQL. Opened on first use: the folder of a SQLite database is created
   * at startup, after this manager.
   */
  private lazy val pools: (HikariDataSource, HikariDataSource) =
    val opened = engine match
      case Const.DbEngineName.POSTGRES =>
        val pool = postgresPool()
        (pool, pool)
      case Const.DbEngineName.SQLITE =>
        val readPoolSize = config.getInt(Const.Conf.SQLITE_READ_POOL_SIZE)
        (sqlitePool("sqlite-read", readPoolSize, isReadPool = true), sqlitePool("sqlite-write", 1, isReadPool = false))
      case other => throw IllegalArgumentException(s"Unknown datasource [$other]")
    isOpen = true
    opened

  /**
   * A connection borrowed from the read or the write pool with a transaction of its own begun: the caller commits or rolls back,
   * and closes it to return it.
   */
  def connection(readOnly: Boolean): Connection =
    val (readPool, writePool) = pools
    val pool = if readOnly then readPool else writePool
    val waitStarted = System.nanoTime
    val conn = pool.getConnection
    val waitedMs = (System.nanoTime - waitStarted) / 1000000
    // SQLite's writers queue for the one write connection, so a long write holds up every writer behind it
    if waitedMs > TransactionManager.SLOW_CONNECTION_WAIT_MS then
      logger.warn(s"Waited ${waitedMs / 1000} s for a connection of the ${pool.getPoolName} pool")
    logger.trace(s"Borrowed connection $conn. Read-only: $readOnly")

    try
      // On SQLite this begins the transaction, IMMEDIATE on the write connection
      conn.setAutoCommit(false)
      if readOnly && engine == Const.DbEngineName.POSTGRES then beginPostgresRead(conn)
      explainer.fold(conn)(ExplainingConnection(conn, _))
    catch
      case ex: Exception =>
        conn.close()
        throw ex

  /** The transaction's own settings, before its first statement; `SET LOCAL` ends with the transaction */
  private def beginPostgresRead(conn: Connection): Unit =
    val timeLimit = settings().map(s => s"; SET LOCAL statement_timeout = ${s.dbPostgresReadStatementTimeout * 1000L}")
    val statement = conn.createStatement()
    try statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY" + timeLimit.getOrElse(""))
    finally statement.close()

  def withTransaction[A](f: => A): A =
    if isInTransaction then f else transaction(readOnly = false)(f)

  /**
   * A write transaction set up to search face vectors ([[vectorSearchSetup]]). On SQLite, whose connections are set up as they
   * open, it is a plain write transaction.
   */
  def withFaceVector[A](f: => A): A = withTransaction(settingUpVectorSearch(limit = 0)(f))

  /**
   * [[withFaceVector]] as a read transaction (`SET LOCAL` is allowed in one), for a search that stores nothing: one snapshot on
   * PostgreSQL, and SQLite's write connection stays free.
   */
  def withFaceVectorReadOnly[A](f: => A): A = asReadOnly(settingUpVectorSearch(limit = 0)(f))

  /** A read transaction set up to find the `limit` nearest rows that pass the statement's filters (a Describe search) */
  def withVectorSearch[A](limit: Int)(f: => A): A = asReadOnly(settingUpVectorSearch(limit)(f))

  /**
   * Runs `f` with the thread's transaction set up for a vector search of up to `limit` rows (0 for face vectors). A nested call
   * sets it up again only for a higher limit, which then stays: `SET LOCAL` lasts until the transaction ends, and a setup for
   * more rows serves a smaller search too.
   */
  private def settingUpVectorSearch[A](limit: Int)(f: => A): A =
    if vectorSearchLimit.value.exists(_ >= limit) then f
    else
      vectorSearchSetup(limit) match
        case Nil => f
        case statements =>
          val statement = RequestContext.getConn.createStatement()
          try statements.foreach(statement.execute)
          finally statement.close()
          vectorSearchLimit.withValue(Some(limit))(f)

  /**
   * The statements a transaction runs before searching a vector column for up to `limit` rows. On PostgreSQL a scan keeps the
   * larger of the vector index search width Setting and `limit` as candidates (`hnsw.ef_search`, capped at pgvector's most), and
   * goes on past them (`hnsw.iterative_scan`), since the statement's filters (repository, bad matches, trash) apply after the
   * index is read. The Setting is read as the setup runs, so a save later in the same transaction can leave a face's match count
   * out of step with it, which `relaxed_order` tolerates.
   *
   * A search with a limit must not stop short while matching rows remain, as pgvector's scan caps would have it (20,000 tuples,
   * which a wide search's candidates alone can pass, and `work_mem`): it lifts the first and raises the second
   * ([[TransactionManager.HNSW_SCAN_MEM_MULTIPLIER]]). A face vector search (`limit` 0) keeps the caps. SQLite's scans are exact
   * and need nothing.
   */
  private def vectorSearchSetup(limit: Int): List[String] = engine match
    case Const.DbEngineName.SQLITE => Nil
    case Const.DbEngineName.POSTGRES =>
      // Nothing is known of the Setting before the Settings are first loaded: a search of the face vectors then sets none
      val candidates = (settings().map(_.faceRecognitionHnswEfSearch).toList ++ Option.when(limit > 0)(limit)).maxOption
      val uncapped =
        if limit > 0 then
          List(
            s"SET LOCAL hnsw.max_scan_tuples = ${TransactionManager.HNSW_UNCAPPED_SCAN_TUPLES}",
            s"SET LOCAL hnsw.scan_mem_multiplier = ${TransactionManager.HNSW_SCAN_MEM_MULTIPLIER}"
          )
        else Nil
      candidates.map(count => s"SET LOCAL hnsw.ef_search = ${count.min(TransactionManager.HNSW_MAX_EF_SEARCH)}").toList ++
        ("SET LOCAL hnsw.iterative_scan = relaxed_order" :: uncapped)

  /**
   * Replaces the pooled SQLite connections once a fresh database has its schema: those opened before it missed the per-table
   * setup ([[setUpSqliteConnection]]), so each is closed as it returns to its pool, which opens a replacement. PostgreSQL
   * connections need nothing of the schema.
   */
  def schemaCreated(): Unit =
    if engine != Const.DbEngineName.SQLITE then return

    val (readPool, writePool) = pools
    Set(readPool, writePool).foreach(_.getHikariPoolMXBean.softEvictConnections())
    logger.debug("Replacing the SQLite connections opened before the schema existed")

  def asReadOnly[A](f: => A): A =
    if isInTransaction then f else transaction(readOnly = true)(f)

  /**
   * Runs `f` under a savepoint of the thread's transaction and answers what it returns. When `undo` holds of that, the
   * transaction is rolled back to the savepoint first, undoing what `f` did, the row locks it took on PostgreSQL included, so a
   * caller that locked the wrong rows can take them again in its order rather than add to what it holds. On SQLite, whose one
   * write connection serializes the writers, there are no row locks to give back.
   */
  def undoingIf[A](f: => A)(undo: A => Boolean): A =
    require(isInTransaction, "A savepoint needs a transaction")
    val conn = RequestContext.getConn
    val savepoint = conn.setSavepoint()
    val result = f
    if undo(result) then conn.rollback(savepoint)
    conn.releaseSavepoint(savepoint)
    result

  /**
   * Runs `action` once the thread's outermost transaction has committed, with no connection held, or at once outside a
   * transaction; a rollback (as every read transaction ends) drops it. For what must not happen unless the change does, such as a
   * log line. A throwing action is logged, never thrown: it fails neither its caller, nor the transaction, nor the actions after
   * it.
   */
  def afterCommit(action: => Unit): Unit =
    afterCommitActions.value match
      case Some(actions) => actions += (() => action)
      case None => runAfterCommit(List(() => action))

  /**
   * Runs `f` in a transaction of its own, on a connection the thread holds until `f` ends. A write is committed; a read has
   * nothing to keep and is rolled back, as is a transaction that failed.
   */
  private def transaction[A](readOnly: Boolean)(f: => A): A =
    val conn = connection(readOnly)
    val actions = ListBuffer[() => Unit]()

    val res =
      try
        val res = RequestContext.conn.withValue(Some(conn))(afterCommitActions.withValue(Some(actions))(f))
        if readOnly then rollback(conn) else conn.commit()
        res
      catch
        case ex: Exception =>
          rollback(conn)
          throw ex
      finally
        // Returns the connection to its pool
        conn.close()

    if !readOnly then runAfterCommit(actions.toList)
    res

  private def runAfterCommit(actions: List[() => Unit]): Unit =
    actions.foreach {
      action =>
        try action()
        catch case NonFatal(ex) => logger.warn(s"An action after a commit failed: ${ex.getMessage}", ex)
    }

  /** A failure is logged, never thrown: the caller gets the failure of the transaction, not of its rollback */
  private def rollback(conn: Connection): Unit =
    try conn.rollback()
    catch case NonFatal(ex) => logger.warn(s"Could not roll back a transaction: ${ex.getMessage}")

  /**
   * Lets SQLite refresh the statistics of the tables that need them, on the write connection between transactions; PostgreSQL's
   * autovacuum analyzes on its own. A failure is logged, never thrown: the statistics are a planner's aid.
   */
  def optimize(): Unit =
    if engine != Const.DbEngineName.SQLITE then return

    try
      val conn = pools._2.getConnection
      try
        val statement = conn.createStatement()
        try statement.execute("PRAGMA optimize")
        finally statement.close()
      finally conn.close()
      logger.trace("SQLite statistics optimized")
    catch case NonFatal(ex) => logger.warn(s"Could not optimize SQLite statistics: ${ex.getMessage}")

  /** Closes the pools and every connection in them */
  def shutdown(): Unit =
    if !isOpen then return

    val (readPool, writePool) = pools
    Set(readPool, writePool).foreach(_.close())
    logger.debug("Connection pools closed")

  private def isInTransaction: Boolean = RequestContext.conn.value.isDefined

  /** What every pool has: its name, its size, and the time after which a connection still held is reported as a leak */
  private def poolConfig(name: String, size: Int): HikariConfig =
    val hikari = new HikariConfig()
    hikari.setPoolName(name)
    hikari.setMaximumPoolSize(size)
    hikari.setLeakDetectionThreshold(config.getDuration(Const.Conf.DB_POOL_LEAK_DETECTION_THRESHOLD).toMillis)
    hikari

  /**
   * The PostgreSQL pool. Unless `db.postgres.pool_size` says otherwise, it has a connection per core, for requests running at
   * once, plus six for the import pipeline (a transaction at most per database stage of a repository's import), and never fewer
   * than 10.
   */
  private def postgresPool(): HikariDataSource =
    val hikari = poolConfig(
      "postgres",
      if config.hasPath(Const.Conf.POSTGRES_POOL_SIZE) then config.getInt(Const.Conf.POSTGRES_POOL_SIZE)
      else math.max(10, Runtime.getRuntime.availableProcessors + 6)
    )
    hikari.setJdbcUrl(config.getString(Const.Conf.POSTGRES_URL))
    hikari.setUsername(config.getString(Const.Conf.POSTGRES_USER))
    hikari.setPassword(config.getString(Const.Conf.POSTGRES_PASSWORD))
    hikari.addDataSourceProperty("options", config.getString(Const.Conf.POSTGRES_OPTIONS))
    logger.trace(s"Opening a PostgreSQL pool of ${hikari.getMaximumPoolSize} connections")
    new HikariDataSource(hikari)

  /** A SQLite pool over the database file. Each connection is set up once, as the pool opens it. */
  private def sqlitePool(name: String, size: Int, isReadPool: Boolean): HikariDataSource =
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL)
    sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
    sqliteConfig.enforceForeignKeys(true)
    sqliteConfig.setBusyTimeout(10000)
    sqliteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY)
    // Negative: 64 MiB, in kibibytes rather than pages
    sqliteConfig.setCacheSize(-65536)
    sqliteConfig.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, "1073741824")
    sqliteConfig.enableLoadExtension(true)
    if !isReadPool then sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)

    val dataSource = new SQLiteDataSource(sqliteConfig) {
      override def getConnection(username: String, password: String): SQLiteConnection =
        val conn = super.getConnection(username, password)
        setUpSqliteConnection(conn, isReadPool)
        conn
    }
    dataSource.setUrl(config.getString(Const.Conf.SQLITE_URL))

    val hikari = poolConfig(name, size)
    hikari.setDataSource(dataSource)
    // The writers queue for the one write connection, so a writer waits for every write ahead of it, not for HikariCP's 30 s
    if !isReadPool then hikari.setConnectionTimeout(config.getDuration(Const.Conf.SQLITE_WRITE_CONNECTION_TIMEOUT).toMillis)
    logger.trace(s"Opening a SQLite pool [$name] of $size connections")
    new HikariDataSource(hikari)

  /**
   * Sets a SQLite connection up past its PRAGMAs: loads the vector extension and registers the columns it scans when their tables
   * exist (a database being created has none yet; see [[schemaCreated]]), and refreshes stale statistics, cheaply, before the
   * connection plans anything. The write connection checkpoints the WAL every 500 pages; read connections refuse writes.
   */
  private def setUpSqliteConnection(conn: Connection, isReadPool: Boolean): Unit =
    // Bound, never spliced into the statement: a native jar unpacks it under the home folder, whose name may hold a quote
    // (`C:\Users\O'Brien`)
    val loading = conn.prepareStatement("SELECT load_extension(?)")
    try
      loading.setString(1, vectorExtensionPath)
      loading.execute()
    finally loading.close()

    val statement = conn.createStatement()
    try
      if hasTable(statement, "face") then
        statement.execute("SELECT vector_init('face', 'features', 'dimension=512,type=FLOAT32,distance=cosine')")
      if hasTable(statement, "asset") then
        statement.execute(
          s"SELECT vector_init('asset', '${SceneModel.Siglip2.column}', " +
            s"'dimension=${SceneModel.Siglip2.dimensions},type=FLOAT32,distance=cosine')")
      statement.execute("PRAGMA optimize=0x10002")
      if isReadPool then statement.execute("PRAGMA query_only=1")
      else statement.execute("PRAGMA wal_autocheckpoint=500")
    finally statement.close()
    logger.trace(s"SQLite connection set up. Read pool: $isReadPool")

  private def hasTable(statement: Statement, name: String): Boolean =
    val tables = statement.executeQuery(s"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'")
    try tables.next()
    finally tables.close()

  /** The sqlite-vector build this process loads into each SQLite connection */
  private lazy val vectorExtensionPath: String =
    val path = TransactionManager.vectorExtensionPath(sys.props.get(TransactionManager.NATIVE_DIR_PROPERTY))
    logger.debug(s"The sqlite-vector extension is at: $path")
    path
