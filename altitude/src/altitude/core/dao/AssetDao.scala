package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

trait AssetDao extends BaseDao:
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
