package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.UserMetadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

trait AssetDao extends BaseDao {
  def getUserMetadata(assetId: String): Option[UserMetadata]

  def setUserMetadata(assetId: String, metadata: UserMetadata): Unit

  def queryNotRecycled(q: Query): QueryResult

  def queryRecycled(q: Query): QueryResult

  def queryAll(q: Query): QueryResult

  override def query(q: Query): QueryResult =
    throw new NotImplementedError("Can only directly query recycled and not recycled data sets")

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit = {

    /**
     * Pedestrian version of this just overwrites fields for old metadata and re-sets it on the asset. A better implementation -
     * for advanced engines - updates only the metadata fields of interest.
     */
    // OPTIMIZE
    val existingMetadata = getUserMetadata(assetId) match {
      case Some(m) => m
      case None => UserMetadata()
    }

    logger.debug(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new UserMetadata(newData)
    logger.debug(s"New metadata -> $newMetadata")

    setUserMetadata(assetId, newMetadata)
  }
}
