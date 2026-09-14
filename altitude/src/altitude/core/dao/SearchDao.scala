package altitude.core.dao

import altitude.core.models.Asset
import altitude.core.models.MapBounds
import altitude.core.models.MapCell
import altitude.core.models.MapLocation
import altitude.core.models.UserMetadataField
import altitude.core.util.BoundingBox
import altitude.core.util.GroupedSearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

trait SearchDao:
  def search(query: SearchQuery): SearchResult

  /** One ordered page of a grouped search: its assets with their group and count data */
  def searchGrouped(query: SearchQuery): GroupedSearchPage

  /** The number of assets a search matches, without reading any of them */
  def count(query: SearchQuery): Int

  /** The map's cells for a viewport: the plotted points in the box, aggregated into square cells of the given size in degrees */
  def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell]

  /** The Locations pinned in the box that hold at least one matching asset, with that count */
  def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation]

  /** The box around every plotted point of the search and their count; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds]

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit
