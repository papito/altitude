package software.altitude.core.dao

import software.altitude.core.Context
import software.altitude.core.models.Asset
import software.altitude.core.models.MetadataField
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

trait SearchDao {
  def search(query: SearchQuery)
            (implicit ctx: Context, txId: TransactionId): SearchResult

  def indexAsset(asset: Asset, metadataFields: Map[String, MetadataField])
                (implicit ctx: Context, txId: TransactionId): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, MetadataField])
                  (implicit ctx: Context, txId: TransactionId): Unit

  def addMetadataValue(asset: Asset, field: MetadataField, value: String)
                      (implicit ctx: Context, txId: TransactionId): Unit

  def addMetadataValues(asset: Asset, field: MetadataField, values: Set[String])
                       (implicit ctx: Context, txId: TransactionId): Unit

}
