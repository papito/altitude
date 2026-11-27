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
  /** One ordered page of a flat search, each asset with its position in the order */
  def search(query: SearchQuery): SearchPage

  def searchGrouped(query: SearchQuery): GroupedSearchPage

  /**
   * One page of a Describe search: the matches whose SigLIP 2 embedding has a cosine of at least `minCosine` to the
   * description's, at most `limit`, nearest first, then by ID. The cursor is a row's cosine distance and ID. The page that
   * crosses the line, the first page or a later one, says where the weaker matches begin (cosine under `weakerRatio` of the
   * nearest's, `SearchPage.weaker`). Sort and grouping are ignored; an asset with no embedding is in no page. The caller's
   * transaction is set up for the vector search (`TransactionManager.withVectorSearch`).
   */
  def describe(query: SearchQuery, ranking: DescribeRanking): SearchPage

  /**
   * How this engine ranks `query`'s matches: through its vector index when it has one and the matches are many, exactly
   * otherwise. A search within a description's matches (`SearchQuery.described`) reads it here once for all its statements.
   */
  def rankingFor(query: SearchQuery, ranking: DescribeRanking): DescribeRanking

  /** Exact, without reading any asset */
  def count(query: SearchQuery): Int

  /** Up to the query's cap; one past the cap means more */
  def cappedCount(query: SearchQuery): Int

  /** Folder ID to how many assets of the default view it holds itself; a folder with none is absent */
  def countByFolder(): Map[String, Int]

  /** The plotted points in the box, aggregated into square cells of `cellDegrees` */
  def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell]

  /** The Locations pinned in the box that hold at least one matching asset, with that count */
  def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation]

  /** The box around every plotted point of the search, and their count */
  def mapBounds(query: SearchQuery): Option[MapBounds]

  /**
   * The ID and name of every candidate of each source asked for, keyed by source even when empty. People are the named, visible,
   * live ones; folders leave out the recycled ones and the root. The Search document is no name source.
   */
  def searchNames(sources: Set[SearchSource]): Map[SearchSource, Seq[SearchName]]

  /**
   * The hits of each positive Search text group, by group index, at most one past `limit`. An asset is a hit once per source it
   * is in. Groups with no hit, and groups that are not positive, have no entry.
   */
  def probeText(text: ResolvedSearchText, limit: Int): Map[Int, Seq[String]]

  def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit
