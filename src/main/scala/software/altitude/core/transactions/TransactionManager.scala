package software.altitude.core.transactions

import com.typesafe.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import software.altitude.core.RequestContext
import software.altitude.core.{ Const => C }

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

object TransactionManager {
  def apply(config: Config): TransactionManager = new TransactionManager(config)
}

class TransactionManager(val config: Config) {

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def connection(readOnly: Boolean): Connection = {
    config.getString(C.Conf.DB_ENGINE) match {
      case C.DbEngineName.POSTGRES =>
        val props = new Properties
        val user = config.getString(C.Conf.POSTGRES_USER)
        props.setProperty("user", user)
        val password = config.getString(C.Conf.POSTGRES_PASSWORD)
        props.setProperty("password", password)
        val url = config.getString(C.Conf.POSTGRES_URL)
        val conn = DriverManager.getConnection(url, props)
        logger.debug(s"Opening connection $conn. Read-only: $readOnly")

        if (readOnly) {
          conn.setReadOnly(true)
        } else {
          conn.setReadOnly(false)
          conn.setAutoCommit(false)
        }

        conn

      case C.DbEngineName.SQLITE =>
        Class.forName("org.sqlite.JDBC")

        val url: String = config.getString(C.Conf.SQLITE_URL)

        val sqliteConfig: SQLiteConfig = new SQLiteConfig()

        val conn = if (readOnly) {
          sqliteConfig.setReadOnly(true)
          DriverManager.getConnection(url, sqliteConfig.toProperties)
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
          statement.execute("PRAGMA wal_checkpoint(TRUNCATE);")
          statement.close()

          writeConnection.setAutoCommit(false)

          writeConnection
        }

        conn
    }
  }

  def withTransaction[A](f: => A): A = {
    if (RequestContext.conn.value.isDefined) {
      return f
    }

    RequestContext.conn.value = Some(connection(readOnly = false))

    try {
      // actual function call
      val res: A = f
      commit()
      res
    } catch {
      case ex: Exception =>
        rollback()
        throw ex
    } finally {
      close()
    }
  }

  def asReadOnly[A](f: => A): A = {
    if (RequestContext.conn.value.isDefined) {
      return f
    }

    RequestContext.conn.value = Some(connection(readOnly = true))

    try {
      f
    } catch {
      case ex: Exception =>
        logger.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        throw ex
    } finally {
      close()
    }
  }

  private def rollback(): Unit = {
    RequestContext.conn.value.get.rollback()
  }

  def close(): Unit = {
    if (RequestContext.conn.value.isDefined && RequestContext.conn.value.get.isClosed) {
      logger.warn("Connection already closed")
      return
    }

    RequestContext.conn.value.get.close()
    RequestContext.conn.value = None
  }

  def commit(): Unit = {
    RequestContext.conn.value.get.commit()
  }
}
