package software.altitude.core.transactions

import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import software.altitude.core.AltitudeAppContext
import software.altitude.core.RequestContext
import software.altitude.core.{Const => C}

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class TransactionManager(val app: AltitudeAppContext) {

  private final val log = LoggerFactory.getLogger(getClass)

  def connection(readOnly: Boolean): Connection = {
    app.config.datasourceType match {
      case C.DatasourceType.POSTGRES =>
        val props = new Properties
        val user = app.config.getString("db.postgres.user")
        props.setProperty("user", user)
        val password = app.config.getString("db.postgres.password")
        props.setProperty("password", password)
        val url = app.config.getString("db.postgres.url")
        val conn = DriverManager.getConnection(url, props)
        log.debug(s"Opening connection $conn. Read-only: $readOnly")

        if (readOnly) {
          conn.setReadOnly(true)
        }
        else {
          conn.setReadOnly(false)
          conn.setAutoCommit(false)
        }

        // println(s"NEW PSQL CONN ${System.identityHashCode(conn)}")
        conn

      case C.DatasourceType.SQLITE =>
        val url: String = app.config.getString("db.sqlite.url")

        val sqliteConfig: SQLiteConfig = new SQLiteConfig()

        if (readOnly) {
          sqliteConfig.setReadOnly(true)
          DriverManager.getConnection(url, sqliteConfig.toProperties)
        } else {
          val writeConnection = DriverManager.getConnection(url, sqliteConfig.toProperties)
          writeConnection.setAutoCommit(false)

          // println(s"NEW SQLITE CONN ${System.identityHashCode(writeConnection)}")
          writeConnection
        }
    }
  }

  def withTransaction[A](f: => A): A = {
    if (RequestContext.conn.value.isDefined) {
        return f
    }

    RequestContext.conn.value = Some(connection(readOnly=false))

    try {
      // actual function call
      val res: A = f
      commit()
      res
    }
    catch {
      case ex: Exception =>
        rollback()
        throw ex
    }
    finally {
      close()
    }
  }

  def asReadOnly[A](f: => A): A = {
    if (RequestContext.conn.value.isDefined) {
      return f
    }

    RequestContext.conn.value = Some(connection(readOnly=true))

    try {
      f
    }
    catch {
      case ex: Exception =>
        log.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        throw ex
    }
    finally {
        close()
    }
  }

  def rollback(): Unit = {
    /* If there are savepoints, rollback to the last one, NOT the entire transaction */
    if (RequestContext.savepoints.value.nonEmpty) {
      rollbackSavepoint()
      return
    }

    // println("ROLLBACK")
    RequestContext.conn.value.get.rollback()
    RequestContext.savepoints.value.clear()
  }

  def close(): Unit = {
    if (RequestContext.conn.value.isDefined && RequestContext.conn.value.get.isClosed) {
      log.warn("Connection already closed")
      return
    }

    RequestContext.conn.value.get.close()
    RequestContext.conn.value = None
    RequestContext.savepoints.value.clear()
  }

  private def rollbackSavepoint(): Unit = {
    if (RequestContext.savepoints.value.isEmpty) {
      return
    }

    // println("PARTIAL ROLLBACK")
    val savepoint = RequestContext.savepoints.value.pop()
    RequestContext.conn.value.get.rollback(savepoint)
  }

  def savepoint(): Unit = {
    val savepoint = RequestContext.conn.value.get.setSavepoint()
    RequestContext.savepoints.value.push(savepoint)
  }

  def commit(): Unit = {
    // println(s"COMMIT ${System.identityHashCode(RequestContext.conn.value.get)}")
    RequestContext.conn.value.get.commit()
    RequestContext.savepoints.value.clear()
  }
}
