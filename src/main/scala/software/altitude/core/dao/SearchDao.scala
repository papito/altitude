package software.altitude.core.dao

import software.altitude.core.models.Asset
import software.altitude.core.models.MetadataField
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

trait SearchDao {
  def search(query: SearchQuery): SearchResult

  def indexAsset(asset: Asset, metadataFields: Map[String, MetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, MetadataField]): Unit

  def addMetadataValue(asset: Asset, field: MetadataField, value: String): Unit

  def addMetadataValues(asset: Asset, field: MetadataField, values: Set[String]): Unit

}
