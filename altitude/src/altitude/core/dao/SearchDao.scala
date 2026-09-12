package altitude.core.dao

import altitude.core.models.Asset
import altitude.core.models.UserMetadataField
import altitude.core.util.GroupedSearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

trait SearchDao:
  def search(query: SearchQuery): SearchResult

  /** One ordered page of a grouped search: its assets with their day and count data */
  def searchGrouped(query: SearchQuery): GroupedSearchPage

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit
