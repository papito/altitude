package software.altitude.core.service
import software.altitude.core.Altitude
import software.altitude.core.FieldConst
import software.altitude.core.dao.AssetDao
import software.altitude.core.models.Asset
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

class AssetService(val app: Altitude) extends BaseService[Asset] {
  override protected val dao: AssetDao = app.DAO.asset

  def setRecycledProp(asset: Asset, isRecycled: Boolean): Unit = {
    if (asset.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction[Unit] {
      logger.info(s"Setting asset [${asset.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(asset.persistedId, Map(FieldConst.Asset.IS_RECYCLED -> isRecycled))
    }
  }

  override def query(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryNotRecycled(q.withRepository())
    }
  }

  def queryRecycled(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryRecycled(q.withRepository())
    }
  }

  def queryAll(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryAll(q.withRepository())
    }
  }

  def pruneDanglingAssets(): Unit = {
    txManager.withTransaction {
      dao.deleteByQuery(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
    }
  }

  def getDanglingAssets: List[Asset] = {
    txManager.asReadOnly {
      val danglingAssets = dao.queryAll(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
      danglingAssets.records.map(Asset.fromJson(_))
    }
  }

  def markAsCompleted(asset: Asset): Asset = {
    txManager.withTransaction {
      val updateData = Map(
        FieldConst.Asset.IS_PIPELINE_PROCESSED -> true
      )

      updateById(asset.persistedId, updateData)
      asset.copy(isPipelineProcessed = true)
    }
  }
}
