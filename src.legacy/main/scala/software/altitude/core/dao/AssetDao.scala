package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Asset
import software.altitude.core.models.UserMetadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

trait AssetDao extends BaseDao {
  def getUserMetadata(assetId: String): Option[UserMetadata]

  def setUserMetadata(assetId: String, metadata: UserMetadata): Unit

  def queryNotRecycled(q: Query): QueryResult

  def queryTriaged(q: Query): QueryResult

  def queryRecycled(q: Query): QueryResult

  def queryAll(q: Query): QueryResult

  override def query(q: Query): QueryResult =
    throw new NotImplementedError("Can only directly query recycled and not recycled data sets")

  def getAssetsToRecycle(assetIds: Set[String]): List[Asset] = throw new NotImplementedError("")

  def getAssetsToMove(assetIds: Set[String], folderId: String): List[Asset] = throw new NotImplementedError("")

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit
}
