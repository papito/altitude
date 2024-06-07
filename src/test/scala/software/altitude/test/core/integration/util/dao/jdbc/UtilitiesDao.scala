package software.altitude.test.core.integration.util.dao.jdbc

import net.codingwell.scalaguice.InjectorExtensions._
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.transactions.JdbcTransactionManager
import software.altitude.core.transactions.TransactionId
import software.altitude.test.core.integration.util.dao

class UtilitiesDao(app: AltitudeCoreApp) extends dao.UtilitiesDao {

  protected final def txManager: JdbcTransactionManager = app.injector.instance[JdbcTransactionManager]

  override def rollback(): Unit = {

    txManager.txRegistry.foreach(tx => {
      tx._2.down()
      tx._2.rollback()
    })
  }

  override def close(): Unit = {
    txManager.txRegistry.foreach(tx => {
      tx._2.down()
      txManager.closeTransaction(tx._2)
    })
  }

  override def cleanupTest(): Unit = {
    rollback()
    close()
    txManager.txRegistry.clear()
  }

  override def createTransaction(txId: TransactionId): Unit = {
    val tx = txManager.transaction()(txId)
    // up one level so it does not get committed or closed
    tx.up()
  }
}
