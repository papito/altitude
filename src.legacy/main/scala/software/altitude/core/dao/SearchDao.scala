package software.altitude.core.dao

import software.altitude.core.models.Asset
import software.altitude.core.models.UserMetadataField
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

trait SearchDao {
  def search(query: SearchQuery): SearchResult

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit

}
