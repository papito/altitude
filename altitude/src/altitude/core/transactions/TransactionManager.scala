package altitude.core.transactions

import com.typesafe.config.Config
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig

import altitude.core.Const
import altitude.core.Environment
import altitude.core.RequestContext

object TransactionManager:
  def apply(config: Config): TransactionManager = new TransactionManager(config)

class TransactionManager(val config: Config):

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def connection(readOnly: Boolean): Connection =
    config.getString(Const.Conf.DB_ENGINE) match
      case Const.DbEngineName.POSTGRES =>
        val props = new Properties
        val user = config.getString(Const.Conf.POSTGRES_USER)
        props.setProperty("user", user)
        val password = config.getString(Const.Conf.POSTGRES_PASSWORD)
        props.setProperty("password", password)
        val url = config.getString(Const.Conf.POSTGRES_URL)
        val conn = DriverManager.getConnection(url, props)
        logger.debug(s"Opening connection $conn. Read-only: $readOnly")

        if readOnly then conn.setReadOnly(true)
        else
          conn.setReadOnly(false)
          conn.setAutoCommit(false)

        conn

      case Const.DbEngineName.SQLITE =>
        Class.forName("org.sqlite.JDBC")

        val url: String = config.getString(Const.Conf.SQLITE_URL)

        val sqliteConfig: SQLiteConfig = new SQLiteConfig()
        sqliteConfig.enableLoadExtension(true)

        val conn = if readOnly then {
          // sqliteConfig.setReadOnly(true)
          val readConn = DriverManager.getConnection(url, sqliteConfig.toProperties)

          readConn
        } else {
          val writeConnection = DriverManager.getConnection(url, sqliteConfig.toProperties)

          val statement = writeConnection.createStatement()
          // enable write-ahead logging and set synchronous to NORMAL for concurrent operations
          statement.execute("PRAGMA journal_mode=WAL;")
          statement.execute("PRAGMA synchronous=NORMAL;")
          statement.execute("PRAGMA isolation_level=IMMEDIATE;")
          // 10s BUSY_TIMEOUT
          statement.execute("PRAGMA busy_timeout=10000;")
          statement.execute("PRAGMA foreign_keys=ON;")
          statement.execute("PRAGMA temp_store=MEMORY;")
          statement.execute("PRAGMA wal_autocheckpoint=500;")
          // statement.execute("PRAGMA wal_checkpoint(TRUNCATE);")
          statement.close()

          writeConnection.setAutoCommit(false)

          writeConnection
        }

        conn

  def withTransaction[A](f: => A): A =
    if RequestContext.conn.value.isDefined && !RequestContext.conn.value.get.isClosed then return f

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

  def withFaceVector[A](f: => A): A =
    withTransaction {
      loadSqliteVectorExtension()
      f
    }

  private def loadSqliteVectorExtension(): Unit =
    config.getString(Const.Conf.DB_ENGINE) match
      case Const.DbEngineName.SQLITE =>
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

        logger.debug("Loading sqlite-vector extension for platform: " + platformDir)
        val vectorLibPath = Environment.resolveResourcePath(s"/sqlite-vector/$platformDir/$extName")
        logger.info(s"Loading sqlite-vector extension from: $vectorLibPath")

        RequestContext.getConn
          .prepareStatement(
            s"SELECT load_extension('$vectorLibPath')"
          )
          .execute()

        RequestContext.getConn
          .prepareStatement(
            "SELECT vector_init('face', 'features', 'dimension=512,type=FLOAT32,distance=cosine')"
          )
          .execute()

      case _ =>
      // Not SQLite

  def asReadOnly[A](f: => A): A =
    if RequestContext.conn.value.isDefined && !RequestContext.conn.value.get.isClosed then return f

    RequestContext.conn.value = Some(connection(readOnly = true))

    try f
    catch
      case ex: Exception =>
        logger.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        throw ex
    finally close()

  private def rollback(): Unit =
    RequestContext.conn.value.get.rollback()

  def close(): Unit =
    if RequestContext.conn.value.isDefined && RequestContext.conn.value.get.isClosed then
      logger.warn("Connection already closed")
      return

    RequestContext.conn.value.get.close()
    RequestContext.conn.value = None

  def commit(): Unit =
    RequestContext.conn.value.get.commit()
