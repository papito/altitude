package software.altitude.core.service
import software.altitude.core.Altitude
import software.altitude.core.dao.AssetDao
import software.altitude.core.models.Asset
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

/**
 * This is a "dumb" DAO service for asset table.
 *
 * All the actual asset management logic is handled by the LibraryService exclusively.
 */
class AssetService(val app: Altitude) extends BaseService[Asset] {
  override protected val dao: AssetDao = app.DAO.asset

  def setRecycledProp(asset: Asset, isRecycled: Boolean): Unit = {

    if (asset.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction[Unit] {
      logger.info(s"Setting asset [${asset.persistedId}] recycled flag to [$isRecycled]")
      val updatedAsset: Asset  = asset.copy(isRecycled = isRecycled)
      dao.updateById(asset.persistedId, data = updatedAsset, fields = List(C.Asset.IS_RECYCLED))
    }
  }

  override def query(q: Query): QueryResult = {
    dao.queryNotRecycled(q.withRepository())
  }

  def queryRecycled(q: Query): QueryResult = {
    dao.queryRecycled(q.withRepository())
  }

  def queryAll(q: Query): QueryResult = {
    dao.queryAll(q.withRepository())
  }

  // NO
  // Read the class description
}
