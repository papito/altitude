package software.altitude.core.transactions

import org.slf4j.LoggerFactory
import software.altitude.core.{Const => C}

import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import scala.collection.mutable

/**
 * JDBC transaction object. It is important to note that most/all methods here should NOT THROW.
 * We assume the methods on the connection are called within catch/finally blocks, and therefore
 * we should let those methods finish.
 *
 * @param conn the connection
 * @param isReadOnly is this connection read-only?
 */
class JdbcTransaction(private val conn: Connection, val isReadOnly: Boolean)
  extends Transaction {

  private final val log = LoggerFactory.getLogger(getClass)
  private val savePoints = new mutable.Stack[Savepoint]

  def getConnection: Connection = conn

  def addSavepoint(): Unit = {
    try {
      val savepoint = conn.setSavepoint()
      log.info(s"ADD SAVEPOINT ${savepoint}")
      savePoints.push(savepoint)
      log.info(s"Now [${savePoints.length}] savepoints")
    }
    catch {
      case e: SQLException => log.error(s"SQL ERROR setting savepoint transaction [$id]: $e")
      case e: Exception => log.error(s"ERROR setting savepoint for transaction [$id]: $e")
    }
  }

  def rollbackSavepoint(): Unit = {
    if (savePoints.isEmpty) {
      return
    }

    log.debug("Rolling back last savepoint")

    try{
      val lastSavepoint: Savepoint = savePoints.pop()
      conn.rollback(lastSavepoint)
      log.info(s"Now [${savePoints.length}] savepoints")
    }
    catch {
      case e: SQLException => log.error(s"SQL ERROR rolling back savepoint transaction [$id]: $e")
      case e: Exception => log.error(s"ERROR rolling back savepoint for transaction [$id]: $e")
    }
  }

  def hasSavepoints: Boolean = savePoints.nonEmpty

  override def close(): Unit = {
    if (!hasParents) {
      log.debug(s"Closing connection for transaction $id", C.LogTag.DB)
      try {
        conn.close()
      }
      catch {
        case e: SQLException => log.error(s"SQL ERROR closing connection for transaction [$id]: $e")
        case e: Exception => log.error(s"ERROR closing connection for transaction [$id]: $e")
      }
    }
  }

  override def commit(): Unit = {
    if (!hasParents) {
      try {
        log.info(s"!COMMIT! $id", C.LogTag.DB)
        conn.commit()
        savePoints.clear()
      }
      catch {
        case e: SQLException => log.error(s"SQL ERROR committing connection for transaction [$id]: $e")
        case e: Exception => log.error(s"ERROR committing connection for transaction [$id]: $e")
      }
    }
  }
  override def rollback(): Unit = {
    try {
      if (!hasParents && !conn.isReadOnly) {
        log.warn(s"!ROLLBACK! $id", C.LogTag.DB)
        conn.rollback()
        savePoints.clear()
      }
      // it's a savepoint
      else if (hasParents && hasSavepoints) {
        log.info(s"!ROLLBACK SAVEPOINT! for $id", C.LogTag.DB)
        rollbackSavepoint()
      }
    }
    catch {
      case e: SQLException => log.error(s"SQL ERROR rolling back connection for transaction [$id]: $e")
      case e: Exception => log.error(s"ERROR rolling back connection for transaction [$id]: $e")
    }
  }
}
