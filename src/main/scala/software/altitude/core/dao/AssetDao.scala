package software.altitude.core.dao

import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Context
import software.altitude.core.models.Metadata
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

trait AssetDao extends BaseDao {
  private final val log = LoggerFactory.getLogger(getClass)

  def getMetadata(assetId: String)(implicit ctx: Context, txId: TransactionId): Option[Metadata]

  def setMetadata(assetId: String, metadata: Metadata)(implicit ctx: Context, txId: TransactionId): Unit

  def queryNotRecycled(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult

  def queryRecycled(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult

  def queryAll(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult

  override def query(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult =
    throw new NotImplementedError("Can only directly query recycled and not recycled data sets")

  override def getAll(implicit ctx: Context, txId: TransactionId): List[JsObject] =
    throw new NotImplementedError

  def updateMetadata(assetId: String, metadata: Metadata, deletedFields: Set[String])
                             (implicit ctx: Context, txId: TransactionId): Unit = {
    /**
     * Pedestrian version of this just overwrites fields for old metadata and re-sets it on the asset.
     * A better implementation - for advanced engines - updates only the metadata fields of interest.
     */
    // OPTIMIZE
    val existingMetadata = getMetadata(assetId) match {
      case Some(m) => m
      case None => Metadata()
    }

    log.debug(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new Metadata(newData)
    log.debug(s"New metadata -> $newMetadata")

    setMetadata(assetId, newMetadata)
  }
}
