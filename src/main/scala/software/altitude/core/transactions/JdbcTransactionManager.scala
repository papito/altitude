package software.altitude.core.transactions

import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.{Const => C}

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class JdbcTransactionManager(val app: AltitudeCoreApp) extends AbstractTransactionManager {
  private final val log = LoggerFactory.getLogger(getClass)

  /**
   * This transaction registry is the heart of it all - we use it to keep track of existing
   * transactions, creating new ones, and cleaning up after a transaction ends.
   */
  val txRegistry: scala.collection.mutable.Map[Int, JdbcTransaction] =
    scala.collection.mutable.Map[Int, JdbcTransaction]()

  /**
   *  Get an existing transaction if we are already within a transaction context, else,
   *  create a new transaction - AND a JDBC connection
   */
  def transaction(readOnly: Boolean = false)(implicit txId: TransactionId): JdbcTransaction = {
    log.debug(s"Getting transaction for ${txId.id}")

    // see if we already have a transaction ID defined
    if (txRegistry.contains(txId.id)) {
      // we do - do nothing, return the transaction
      return txRegistry(txId.id)
    }

    // get a connection and a new transaction, read-only if so requested
    val conn: Connection = connection(readOnly)

    val tx: JdbcTransaction = new JdbcTransaction(conn, readOnly)

    // add this to our transaction registry
    txRegistry += (tx.id -> tx)

    // assign the integer transaction ID to the mutable transaction id "carrier" object
    txId.id = tx.id
    log.debug(s"CREATING TRANSACTION ${txId.id}")
    transactions.CREATED += 1
    tx
  }

  def closeConnection(conn: Connection): Unit = {
    log.info(s"Closing connection $conn")
    conn.close()
  }

  def closeTransaction(tx: JdbcTransaction): Unit = {
    tx.close()
  }

  /**
   * JDBC-specific connection snooze-fest
   */
  // FIXME: Should be in (new) Postgres manager
  def connection(readOnly: Boolean): Connection = {
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

    conn
  }

  /**
   * This is defined for any JDBC driver that is not thread-safe for writes (Sqlite)
   */
  protected def lock(tx: Transaction): Unit = {}
  protected def unlock(tx: Transaction): Unit = {}

  override def withTransaction[A](f: => A)(implicit txId: TransactionId = new TransactionId): A = {
    log.debug("WRITE transaction")
    val tx = transaction()

    if (tx.isReadOnly) {
      throw new IllegalStateException("The parent for this transaction is read-only!")
    }

    try {
      lock(tx) // this is a no-op for a "real" database
      // level up - any new transactions within will be "nested" and not committed
      tx.up()

      // actual function call
      val res: A = f

      // level down - if the transaction is not nested after this - it will be committed
      tx.down()

      // commit exiting transactions
      tx.commit()
      if (tx.mustCommit) {
        transactions.COMMITTED += 1
      }

      res
    }
    catch {
      case ex: Exception =>
        tx.down()
        log.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        tx.rollback()
        throw ex
    }
    finally {
      // clean up, if we are done with this transaction
      if (!tx.hasParents) {
        log.debug(s"Closing: ${tx.id}", C.LogTag.DB)
        txRegistry.remove(txId.id)
        closeTransaction(tx)
        transactions.CLOSED += 1
      }

      unlock(tx)
    }
  }

  override def asReadOnly[A](f: => A)(implicit txId: TransactionId = new TransactionId): A = {
    log.debug("READ transaction")
    val tx = transaction(readOnly = true)

    try {
      tx.up()  // level up - any new transactions within will be "nested" and use the same connection
      f
    }
    catch {
      case ex: Exception =>
        log.error(s"Error (${ex.getClass.getName}): ${ex.getMessage}")
        throw ex
    }
    finally {
      // unlike write transactions, we can level down here, as we are not committing read-only connections
      tx.down()

      if (!tx.hasParents) {
        log.debug(s"End: ${tx.id}", C.LogTag.DB)
        log.debug(s"Closing: ${tx.id}", C.LogTag.DB)
        txRegistry.remove(txId.id)
        closeTransaction(tx)
        transactions.CLOSED += 1
      }
    }
  }

  override def savepoint()(implicit txId: TransactionId): Unit = {
    transaction().addSavepoint()
  }
}
