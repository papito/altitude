package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Metadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

trait AssetDao extends BaseDao {
  def getMetadata(assetId: String): Option[Metadata]

  def setMetadata(assetId: String, metadata: Metadata): Unit

  def queryNotRecycled(q: Query): QueryResult

  def queryRecycled(q: Query): QueryResult

  def queryAll(q: Query): QueryResult

  override def query(q: Query): QueryResult =
    throw new NotImplementedError("Can only directly query recycled and not recycled data sets")

  def updateMetadata(assetId: String, metadata: Metadata, deletedFields: Set[String]): Unit = {
    /**
     * Pedestrian version of this just overwrites fields for old metadata and re-sets it on the asset.
     * A better implementation - for advanced engines - updates only the metadata fields of interest.
     */
    // OPTIMIZE
    val existingMetadata = getMetadata(assetId) match {
      case Some(m) => m
      case None => Metadata()
    }

    logger.debug(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new Metadata(newData)
    logger.debug(s"New metadata -> $newMetadata")

    setMetadata(assetId, newMetadata)
  }
}
