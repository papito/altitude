package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.dao.AssetDao
import software.altitude.core.models.Asset
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

/**
 * This is a "dumb" service - meaning it delegates everything to to the base service implementation
 * and the base DAO. It does the basics, but shall not do anything more than that.
 *
 * If there is anything special to be done with an asset, it's under
 * the jurisdiction of the Library service - it does all the counter decrementin' and wrist slappin'
 */
class AssetService(val app: Altitude) extends BaseService[Asset] {
  private final val log = LoggerFactory.getLogger(getClass)
  override protected val dao: AssetDao = app.injector.instance[AssetDao]

  def setRecycledProp(asset: Asset, isRecycled: Boolean)
                     (implicit ctx: Context, txId: TransactionId): Unit = {

    if (asset.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction[Unit] {
      log.info(s"Setting asset [${asset.id.get}] recycled flag to [$isRecycled]")
      val updatedAsset = asset.modify(C.Asset.IS_RECYCLED -> isRecycled)
      dao.updateById(asset.id.get, data = updatedAsset, fields = List(C.Asset.IS_RECYCLED))
    }
  }

  override def query(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult = {
    dao.queryNotRecycled(q)
  }

  def queryRecycled(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult = {
    dao.queryRecycled(q)
  }

  def queryAll(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult = {
    dao.queryAll(q)
  }

  // NO
  // Read the class description
}
