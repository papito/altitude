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

  def queryRecycled(q: Query): QueryResult[Asset]

  def queryAll(q: Query): QueryResult[Asset]

  override def query(q: Query): QueryResult[Asset] =
    throw NotImplementedError("Can only directly query recycled and not recycled data sets")

  /*
   * The rows a library operation may change, read and locked in one select: the context repository's assets whose import is
   * complete and that are not marked for purging. An operation counts its stat changes from these rows and updates exactly them.
   */

  /** The live assets among the IDs */
  def getAssetsToRecycle(assetIds: Set[String]): List[Asset]

  /** The live and recycled assets among the IDs */
  def getAssetsToMove(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs */
  def getAssetsToRestore(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs, or the whole recycle bin */
  def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset]

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit
