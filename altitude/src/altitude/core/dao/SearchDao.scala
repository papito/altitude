package altitude.core.dao

import altitude.core.models.Asset
import altitude.core.models.MapBounds
import altitude.core.models.MapCell
import altitude.core.models.MapLocation
import altitude.core.models.UserMetadataField
import altitude.core.util.BoundingBox
import altitude.core.util.DescribeRanking
import altitude.core.util.GroupedSearchPage
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchName
import altitude.core.util.SearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSource

trait SearchDao:
  /** One ordered page of a flat search: its assets, each with where it stands in the order */
  def search(query: SearchQuery): SearchPage

  /** One ordered page of a grouped search: its assets with their group and count data */
  def searchGrouped(query: SearchQuery): GroupedSearchPage

  /**
   * One page of a Describe search: the assets `query` matches, in the context repository, whose SigLIP 2 Scene embedding is at a
   * cosine of at least the ranking's `minCosine` to its `embedding`, a description's, the nearest `limit` of them at most,
   * nearest first and then by ID. The query is resolved as any search's is, and carries the page: the `rpp` rows after its
   * cursor, whose position is a row's cosine distance and ID, or the first ones without it, each row standing at its distance
   * (`SearchRow.sortValue`, a `SortValue.Real`); a page that counts the total (`SearchQuery.isTotalCounted`) carries the count of
   * all the ranked rows, and the page on which the search's weaker matches begin, the ranked rows whose cosine is under the
   * ranking's `weakerRatio` of the nearest one's, says where (`SearchPage.weaker`). Its sort and grouping are not read. An asset
   * with no embedding (a Video, an animated image, a still image the model could not embed) is in no page. The caller's
   * transaction is set up for a vector search of that many rows (`TransactionManager.withVectorSearch`).
   */
  def describe(query: SearchQuery, ranking: DescribeRanking): SearchPage

  /**
   * The ranking as this engine reads it for the matches of `query`, a resolved search with no description of its own: through its
   * vector index when it has one and the matches are many (`DescribeRanking.throughIndex`), exactly otherwise. `describe` reads
   * its ranking so itself; a search among a part of a description's matches (`SearchQuery.described`) is handed the ranking read
   * here, once for its pages' statements.
   */
  def rankingFor(query: SearchQuery, ranking: DescribeRanking): DescribeRanking

  /** The number of assets a search matches, exact, without reading any of them */
  def count(query: SearchQuery): Int

  /** The number of assets a search matches up to the query's cap; one past the cap means more than it */
  def cappedCount(query: SearchQuery): Int

  /** How many assets of the default view each folder holds itself, by folder ID; a folder with none is absent */
  def countByFolder(): Map[String, Int]

  /** The map's cells for a viewport: the plotted points in the box, aggregated into square cells of the given size in degrees */
  def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell]

  /** The Locations pinned in the box that hold at least one matching asset, with that count */
  def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation]

  /** The box around every plotted point of the search and their count; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds]

  /**
   * The ID and name of every candidate of each of the name sources asked for, keyed by each of them, even one with no candidates:
   * people who are named and neither hidden, merged away nor a bad match; Locations with their Category as the parent;
   * Categories; folders with their parent, less the recycled ones and the root; albums. A source not asked for is not read, and
   * the Search document is no name source.
   */
  def searchNames(sources: Set[SearchSource]): Map[SearchSource, Seq[SearchName]]

  /**
   * The hits of each positive group of the Search text, by group index, one past `limit` at most: a group with at most `limit`
   * hits has none other. An asset is a hit once per source it is in. Groups with no hit, and groups that are not positive, have
   * no entry.
   */
  def probeText(text: ResolvedSearchText, limit: Int): Map[Int, Seq[String]]

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit
