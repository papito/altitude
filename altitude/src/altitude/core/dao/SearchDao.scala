package altitude.core.dao

import altitude.core.models.Asset
import altitude.core.models.MapBounds
import altitude.core.models.MapCell
import altitude.core.models.MapLocation
import altitude.core.models.UserMetadataField
import altitude.core.util.BoundingBox
import altitude.core.util.GroupedSearchPage
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchName
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchSource

trait SearchDao:
  def search(query: SearchQuery): SearchResult

  /** One ordered page of a grouped search: its assets with their group and count data */
  def searchGrouped(query: SearchQuery): GroupedSearchPage

  /** The number of assets a search matches, exact, without reading any of them */
  def count(query: SearchQuery): Int

  /** The number of assets a search matches up to the query's cap; one past the cap means more than it */
  def cappedCount(query: SearchQuery): Int

  /** The map's cells for a viewport: the plotted points in the box, aggregated into square cells of the given size in degrees */
  def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell]

  /** The Locations pinned in the box that hold at least one matching asset, with that count */
  def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation]

  /** The box around every plotted point of the search and their count; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds]

  /**
   * The ID and name of every candidate of each name source a Search text can match, keyed by every name source, even one with no
   * candidates: people who are named and neither hidden, merged away nor a bad match; Locations with their Category as the
   * parent; Categories; folders with their parent, less the recycled ones and the root; albums.
   */
  def searchNames: Map[SearchSource, Seq[SearchName]]

  /**
   * The hits of each positive group of the Search text, by group index, one past `limit` at most: a group with at most `limit`
   * hits has none other. An asset is a hit once per source it is in. Groups with no hit, and groups that are not positive, have
   * no entry.
   */
  def probeText(text: ResolvedSearchText, limit: Int): Map[Int, Seq[String]]

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit
