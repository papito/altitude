package software.altitude.core.transactions

import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Configuration

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock

object SqliteTransactionManager {
  private val config = new Configuration()
  val url: String = config.getString("db.sqlite.url")
  private val lock = new ReentrantLock()

  val sqliteConfig: SQLiteConfig = new SQLiteConfig()
  sqliteConfig.setReadOnly(true)
  private lazy val readOnlyConnection = DriverManager.getConnection(url, sqliteConfig.toProperties)
  private lazy val writeConnection = DriverManager.getConnection(url)
  writeConnection.setAutoCommit(false)
}

class SqliteTransactionManager(app: AltitudeCoreApp)
  extends JdbcTransactionManager(app) {
  private final val log = LoggerFactory.getLogger(getClass)

  override def connection(readOnly: Boolean = false): Connection = {
    val conn = if (readOnly) {
      SqliteTransactionManager.readOnlyConnection
    }
    else {
      SqliteTransactionManager.writeConnection
    }

    log.debug(s"Getting connection $conn. Read-only: $readOnly")
    conn
  }

  override def lock(tx: Transaction): Unit = {
    if (!tx.hasParents) {
      log.debug("Acquiring SQLite write lock")
      SqliteTransactionManager.lock.lock()
    }
  }

  override def unlock(tx: Transaction): Unit = {
    if (!tx.hasParents) {
      log.debug("releasing SQLite write lock")
      SqliteTransactionManager.lock.unlock()
    }
  }

  override def closeTransaction(tx: JdbcTransaction): Unit = {}
  override def closeConnection(conn: Connection): Unit = {}
}
