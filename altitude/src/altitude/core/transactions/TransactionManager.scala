package altitude.core.transactions

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteDataSource

import scala.util.control.NonFatal

import altitude.core.Const
import altitude.core.Environment
import altitude.core.RequestContext

object TransactionManager:
  def apply(config: Config): TransactionManager = new TransactionManager(config)

/**
 * Connections and the transactions on them. Every transaction runs on a connection borrowed from a HikariCP pool and carried by
 * `RequestContext.conn`; a transaction started inside another joins it.
 *
 * PostgreSQL has one pool, every connection opened with `db.postgres.options`. A read transaction is `REPEATABLE READ` and
 * `READ ONLY`, so all of its statements see one snapshot, and each of its statements has the `db.postgres.read_statement_timeout`
 * time limit; a write transaction is the engine's default `READ COMMITTED`.
 *
 * SQLite has a read pool and a write pool of one connection over the same file, every connection opened with the same PRAGMAs. A
 * write transaction begins `IMMEDIATE`, taking the write lock when it starts, and the single write connection queues writers in
 * the process instead of failing them with `SQLITE_BUSY`. A read transaction reads one WAL snapshot, and its connection refuses
 * writes (`query_only`): a write nested in a read transaction would otherwise be discarded by its closing rollback.
 *
 * Both engines end a read transaction with a rollback: it has nothing to keep.
 */
class TransactionManager(val config: Config):

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val engine: String = config.getString(Const.Conf.DB_ENGINE)

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
      conn
    catch
      case ex: Exception =>
        conn.close()
        throw ex

  /** The transaction's own settings, before its first statement; `SET LOCAL` ends with the transaction */
  private def beginPostgresRead(conn: Connection): Unit =
    val statement = conn.createStatement()
    try
      statement.execute(
        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; " +
          s"SET LOCAL statement_timeout = ${config.getDuration(Const.Conf.POSTGRES_READ_STATEMENT_TIMEOUT).toMillis}")
    finally statement.close()

  def withTransaction[A](f: => A): A =
    if isInTransaction then return f

    RequestContext.conn.value = Some(connection(readOnly = false))

    try
      // actual function call
      val res: A = f
      commit()
      res
    catch
      case ex: Exception =>
        rollback()
        throw ex
    finally close()

  /**
   * A write transaction that can search face vectors: on SQLite the `face.features` column is registered with the vector
   * extension, which every connection loads when it is opened, before the transaction's first statement. Registering needs the
   * `face` table, which a database being created does not have yet when its first connections are opened.
   */
  def withFaceVector[A](f: => A): A =
    withTransaction {
      if engine == Const.DbEngineName.SQLITE then
        val statement = RequestContext.getConn.createStatement()
        try statement.execute("SELECT vector_init('face', 'features', 'dimension=512,type=FLOAT32,distance=cosine')")
        finally statement.close()
      f
    }

  def asReadOnly[A](f: => A): A =
    if isInTransaction then return f

    RequestContext.conn.value = Some(connection(readOnly = true))

    try f
    catch
      case ex: Exception =>
        logger.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        throw ex
    finally
      try rollback()
      catch case NonFatal(ex) => logger.warn(s"Could not end a read transaction: ${ex.getMessage}")
      finally close()

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

  private def isInTransaction: Boolean = RequestContext.conn.value.exists(conn => !conn.isClosed)

  private def rollback(): Unit =
    RequestContext.conn.value.get.rollback()

  def close(): Unit =
    if RequestContext.conn.value.isDefined && RequestContext.conn.value.get.isClosed then
      logger.warn("Connection already closed")
      return

    // Returns the connection to its pool
    RequestContext.conn.value.get.close()
    RequestContext.conn.value = None

  def commit(): Unit =
    RequestContext.conn.value.get.commit()

  /**
   * The PostgreSQL pool. Unless `db.postgres.pool_size` says otherwise, it has a connection per core, for requests running at
   * once, plus four for the import pipeline (a transaction at most per asynchronous stage of each repository's import), and never
   * fewer than 10.
   */
  private def postgresPool(): HikariDataSource =
    val hikari = new HikariConfig()
    hikari.setPoolName("postgres")
    hikari.setJdbcUrl(config.getString(Const.Conf.POSTGRES_URL))
    hikari.setUsername(config.getString(Const.Conf.POSTGRES_USER))
    hikari.setPassword(config.getString(Const.Conf.POSTGRES_PASSWORD))
    hikari.setMaximumPoolSize(
      if config.hasPath(Const.Conf.POSTGRES_POOL_SIZE) then config.getInt(Const.Conf.POSTGRES_POOL_SIZE)
      else math.max(10, Runtime.getRuntime.availableProcessors + 4))
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

    val hikari = new HikariConfig()
    hikari.setPoolName(name)
    hikari.setDataSource(dataSource)
    hikari.setMaximumPoolSize(size)
    logger.trace(s"Opening a SQLite pool [$name] of $size connections")
    new HikariDataSource(hikari)

  /**
   * What a SQLite connection needs past its PRAGMAs: the vector extension, and the statistics of the tables that need them
   * (cheap, and before the connection plans anything). The write connection checkpoints the WAL every 500 pages; a read
   * connection then refuses writes.
   */
  private def setUpSqliteConnection(conn: Connection, isReadPool: Boolean): Unit =
    val statement = conn.createStatement()
    try
      statement.execute(s"SELECT load_extension('$vectorExtensionPath')")
      statement.execute("PRAGMA optimize=0x10002")
      if isReadPool then statement.execute("PRAGMA query_only=1")
      else statement.execute("PRAGMA wal_autocheckpoint=500")
    finally statement.close()
    logger.trace(s"SQLite connection set up. Read pool: $isReadPool")

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
