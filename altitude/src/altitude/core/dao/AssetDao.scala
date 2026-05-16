package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

trait AssetDao extends BaseDao[Asset]:
  def getUserMetadata(assetId: String): Option[UserMetadata]

  def setUserMetadata(assetId: String, metadata: UserMetadata): Unit

  def queryNotRecycled(q: Query): QueryResult[Asset]

  def queryTriaged(q: Query): QueryResult[Asset]

  def queryRecycled(q: Query): QueryResult[Asset]

  def queryAll(q: Query): QueryResult[Asset]

  override def query(q: Query): QueryResult[Asset] =
    throw NotImplementedError("Can only directly query recycled and not recycled data sets")

  def getAssetsToRecycle(assetIds: Set[String]): List[Asset] = throw NotImplementedError("")

  def getAssetsToMove(assetIds: Set[String], folderId: String): List[Asset] = throw NotImplementedError("")

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit
