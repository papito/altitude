package altitude.core.transactions

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import altitude.core.Environment
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
   * The memory a scan of a vector search may hold, as a multiple of `work_mem` (`hnsw.scan_mem_multiplier`): a scan that goes on
   * holds what it has visited and stops when that passes the cap. Measured on pgvector 0.8.2, a scan to the end of an index of a
   * million vectors of 768 dimensions takes between 128 and 192 MB, and about 14 seconds: with the connections' 64 MB of
   * `work_mem` it finds a third of the rows that pass its filter under a multiplier of 1, two thirds under 2, and all of them
   * from 3 on. At 8, 512 MB, the read statement timeout ends a scan that long before its memory does.
   */
  private val HNSW_SCAN_MEM_MULTIPLIER = 8

/**
 * Connections and the transactions on them. Every transaction runs on a connection borrowed from a HikariCP pool and carried by
 * `RequestContext.conn` for as long as it lasts; a transaction started inside another, on the same thread, joins it, and a thread
 * started inside one is outside of it. A pool logs a connection held longer than `db.pool.leak_detection_threshold`.
 *
 * PostgreSQL has one pool, every connection opened with `db.postgres.options`. A read transaction is `REPEATABLE READ` and
 * `READ ONLY`, so all of its statements see one snapshot, and each of its statements has the read statement timeout Setting as
 * its time limit; a write transaction is the engine's default `READ COMMITTED`.
 *
 * SQLite has a read pool and a write pool of one connection over the same file, every connection opened with the same PRAGMAs. A
 * write transaction begins `IMMEDIATE`, taking the write lock when it starts, and the single write connection queues writers in
 * the process instead of failing them with `SQLITE_BUSY`. A read transaction reads one WAL snapshot, and its connection refuses
 * writes (`query_only`): a "write" nested in a read transaction would otherwise be discarded by its closing rollback.
 *
 * Both engines end a read transaction with a rollback: it has nothing to keep. A rollback that fails is logged, so a failed
 * transaction always fails with its own exception.
 *
 * A manager given an `explainer` (development only) hands out each connection wrapped in an [[ExplainingConnection]], which
 * offers the explainer every DML statement prepared on it. Without one, a transaction runs on the pooled connection itself.
 *
 * `settings` is the current Settings snapshot, read as each transaction begins so a saved value is used by the next one, or
 * nothing before the first load: the migration of a fresh database runs then, and its transactions set no time limit.
 */
class TransactionManager(val config: Config, explainer: Option[SqlExplainer], settings: () => Option[Settings]):

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val engine: String = config.getString(Const.Conf.DB_ENGINE)

  // The limit of the vector search that set this thread's transaction up ([[settingUpVectorSearch]]), 0 for the face vectors'
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
    val conn = (if readOnly then readPool else writePool).getConnection
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
    val timeLimit = settings().map(s => s"; SET LOCAL statement_timeout = ${s.dbPostgresReadStatementTimeout * 1000}")
    val statement = conn.createStatement()
    try statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY" + timeLimit.getOrElse(""))
    finally statement.close()

  def withTransaction[A](f: => A): A =
    if isInTransaction then f else transaction(readOnly = false)(f)

  /**
   * A write transaction that can search face vectors: what the engine needs for it ([[vectorSearchSetup]], for no more rows than
   * the vector index search width Setting keeps candidates) is run once, for the outermost call. A SQLite connection is set up
   * for it as it is opened ([[setUpSqliteConnection]]), so there the call is a plain write transaction.
   */
  def withFaceVector[A](f: => A): A = withTransaction(settingUpVectorSearch(limit = 0)(f))

  /**
   * A read transaction that can search face vectors: [[asReadOnly]], set up as [[withFaceVector]] sets a write up (`SET LOCAL` is
   * allowed in a read-only transaction). A search that stores nothing reads one snapshot on PostgreSQL and takes none of SQLite's
   * one write connection.
   */
  def withFaceVectorReadOnly[A](f: => A): A = asReadOnly(settingUpVectorSearch(limit = 0)(f))

  /**
   * A read transaction that can search a vector column for the `limit` rows nearest a vector, among the rows its statement's
   * filters pass: [[asReadOnly]], set up for a search that long ([[vectorSearchSetup]]). A Describe search's.
   */
  def withVectorSearch[A](limit: Int)(f: => A): A = asReadOnly(settingUpVectorSearch(limit)(f))

  /**
   * Runs `f` with the thread's transaction set up for a vector search of up to `limit` rows, 0 for a search of the face vectors.
   * The outermost call of the thread sets it up, and a call nested in it does so again only for a higher limit, which then stays
   * for the rest of the transaction: `SET LOCAL` lasts until it ends, and a search set up for more rows than it asks for finds
   * its own.
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
   * What a transaction runs before it searches a vector column for up to `limit` rows. PostgreSQL sets, for the transaction, how
   * its vector indexes are searched. The candidates a scan keeps (`hnsw.ef_search`) are the vector index search width Setting's,
   * read when the setup runs, so a save later in the same transaction can leave a face's match count out of step with it, which
   * `iterative_scan = relaxed_order` tolerates, or `limit` when that is more, up to pgvector's most. A scan goes on past its
   * candidates until the statement's limit is met (`hnsw.iterative_scan`), since the statement's filters (the repository, the bad
   * matches, the trash) are applied after the index is read.
   *
   * A search with a limit must not end short of it while rows that pass the filters are left, as pgvector's own caps on a scan
   * that goes on would have it: 20,000 tuples visited, which the candidates of a wide search alone can pass, and `work_mem` of
   * memory. So it lifts the first and raises the second ([[TransactionManager.HNSW_SCAN_MEM_MULTIPLIER]]), and the limit, the end
   * of the index or the read statement timeout ends its scan. A search of the face vectors (`limit` 0) asks for a few candidates
   * and keeps pgvector's caps.
   *
   * SQLite needs nothing: its connections are set up when they are opened, and its scans are exact.
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
   * Replaces the pooled connections once a fresh database has its schema. A SQLite connection is set up for the schema as it is
   * opened ([[setUpSqliteConnection]]), which the ones opened before the schema existed, while the database's version was read
   * and its schema created, could not be: each is closed as it is returned to its pool, which opens a replacement. A PostgreSQL
   * connection is set up with nothing of the schema.
   */
  def schemaCreated(): Unit =
    if engine != Const.DbEngineName.SQLITE then return

    val (readPool, writePool) = pools
    Set(readPool, writePool).foreach(_.getHikariPoolMXBean.softEvictConnections())
    logger.debug("Replacing the SQLite connections opened before the schema existed")

  def asReadOnly[A](f: => A): A =
    if isInTransaction then f else transaction(readOnly = true)(f)

  /**
   * Runs `action` once the thread's outermost transaction has committed, with no connection held, or at once outside a
   * transaction; a transaction that rolls back, which every read transaction does, drops it. For what must not be seen to happen
   * unless the change it reports does, such as a log line. An action that throws is logged at WARN and does not fail the
   * committed transaction, nor stop the actions after it.
   */
  def afterCommit(action: => Unit): Unit =
    afterCommitActions.value match
      case Some(actions) => actions += (() => action)
      case None => action

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
    logger.trace(s"Opening a SQLite pool [$name] of $size connections")
    new HikariDataSource(hikari)

  /**
   * What a SQLite connection needs past its PRAGMAs: the vector extension, with the two columns it scans registered with it, a
   * Face's `face.features` and an asset's SigLIP 2 Scene embedding, each when its table exists (a database being created has no
   * tables when its first connections are opened; see [[schemaCreated]]), and the statistics of the tables that need them (cheap,
   * and before the connection plans anything). The write connection checkpoints the WAL every 500 pages; a read connection then
   * refuses writes.
   */
  private def setUpSqliteConnection(conn: Connection, isReadPool: Boolean): Unit =
    val statement = conn.createStatement()
    try
      statement.execute(s"SELECT load_extension('$vectorExtensionPath')")
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

  /** The sqlite-vector build for this platform */
  private lazy val vectorExtensionPath: String =
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase

    val (platformDir, extName) =
      if os.contains("mac") || os.contains("darwin") then
        val dir = if arch.contains("aarch64") || arch.contains("arm") then "macos-arm64" else "macos-x86"
        (dir, "vector.dylib")
      else if os.contains("win") then ("windows-x86", "vector.dll")
      else
        // Linux / other Unix
        val dir = if arch.contains("aarch64") || arch.contains("arm") then "linux-arm64" else "linux-x86"
        (dir, "vector.so")

    val path = Environment.resolveResourcePath(s"/sqlite-vector/$platformDir/$extName")
    logger.debug(s"The sqlite-vector extension is at: $path")
    path
