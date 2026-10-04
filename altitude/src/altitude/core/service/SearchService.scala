package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.dao.SearchDao
import altitude.core.models.Asset
import altitude.core.models.MapBounds
import altitude.core.models.MapCells
import altitude.core.models.UserMetadataField
import altitude.core.transactions.TransactionManager
import altitude.core.util.BoundingBox
import altitude.core.util.GroupedSearchResult
import altitude.core.util.ResolvedSearchGroup
import altitude.core.util.ResolvedSearchTerm
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchCursor
import altitude.core.util.SearchExpression
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchSource
import altitude.core.util.SearchTerm
import altitude.core.util.SearchWords
import altitude.core.util.SortValue

object SearchService:

  /** The map's zoom levels: a web-mercator tile pyramid, from the whole world in one tile to street level */
  val MIN_ZOOM = 0
  val MAX_ZOOM = 20

  /**
   * A cell is a quarter of a tile's width at the zoom, about 64 pixels on screen, on both axes; a zoom outside the range is
   * clamped
   */
  def cellDegrees(zoom: Int): Double = 360.0 / math.pow(2, zoom.max(MIN_ZOOM).min(MAX_ZOOM)) / 4

class SearchService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val searchDao: SearchDao = app.DAO.search
  protected val txManager: TransactionManager = app.txManager

  def indexAsset(asset: Asset): Unit =
    require(asset.id.isDefined, "Asset ID cannot be empty")
    logger.trace(s"Indexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.indexAsset(asset, metadataFields)
    }

  def reindexAsset(asset: Asset): Unit =
    logger.trace(s"Reindexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.reindexAsset(asset, metadataFields)
    }

  /**
   * The Search text with each term resolved against the names of the repository's people, Locations, Categories, folders and
   * albums, none of which is stored in a Search document: the candidates of every name source are read in one read-only
   * transaction and matched in memory by the readings of their [[SearchWords]], the rule a document is matched by. A Category's
   * matches become the IDs of its Locations, and a folder's the folder with every folder below it, so each source is a plain ID
   * filter for the search that follows.
   *
   * In the same transaction the resolution is probed ([[probed]]), which decides how the search matches the text.
   */
  def resolveText(expression: SearchExpression, probeLimit: Int = Const.Search.TEXT_PROBE_LIMIT): ResolvedSearchText =
    txManager.asReadOnly {
      val started = System.currentTimeMillis
      val names = searchDao.searchNames
      // The readings of each name once, however many terms there are
      val variants = names.view.mapValues(_.map(name => name.id -> SearchWords.variants(name.name))).toMap
      val locationsByCategory = names(SearchSource.Location).groupMap(_.parentId)(_.id)
      val foldersByParent = names(SearchSource.Folder).groupMap(_.parentId)(_.id)

      /** The folders with every folder below them, level by level; a folder already found is never revisited */
      @tailrec def withDescendants(found: Set[String], level: Set[String]): Set[String] =
        val children = level.flatMap(id => foldersByParent.getOrElse(Some(id), Nil)) -- found
        if children.isEmpty then found else withDescendants(found ++ children, children)

      def resolve(term: SearchTerm): ResolvedSearchTerm =
        val hits = variants.view.mapValues(_.collect { case (id, nameVariants) if term.isIn(nameVariants) => id }.toSet).toMap
        val ids = hits
          .updated(SearchSource.Category, hits(SearchSource.Category).flatMap(id => locationsByCategory.getOrElse(Some(id), Nil)))
          .updated(SearchSource.Folder, withDescendants(hits(SearchSource.Folder), hits(SearchSource.Folder)))
        ResolvedSearchTerm(term, ids.filter((_, matching) => matching.nonEmpty))

      val resolved =
        probed(
          ResolvedSearchText(expression.groups.map(group => ResolvedSearchGroup(group.alternatives.map(resolve)))),
          probeLimit)

      logger.trace(
        s"Resolved the Search text against ${names.values.map(_.size).sum} names in ${System.currentTimeMillis - started}ms: " +
          resolved.groups
            .flatMap(_.alternatives)
            .map(
              term =>
                s"[${term.term.variants.map(_.mkString(" ")).mkString(" | ")}] " +
                  term.ids.map((source, ids) => s"$source=${ids.size}").mkString(" "))
            .mkString(", ") +
          resolved.candidates.fold(". Broad: matched over the library")(
            candidates => s". Selective: ${candidates.size} candidates"))

      resolved
    }

  /**
   * The resolution with what its probe found: each positive group whose hits number at most the limit is complete, and the
   * candidates are the intersection of the complete groups' hits. Text with no complete group, or with no positive group at all,
   * which is not probed, is left broad.
   */
  private def probed(text: ResolvedSearchText, limit: Int): ResolvedSearchText =
    val positive = text.groups.indices.filter(text.groups(_).isPositive)
    if positive.isEmpty then return text

    val hits = searchDao.probeText(text, limit)
    val complete = positive.filter(index => hits.getOrElse(index, Nil).size <= limit).toSet
    ResolvedSearchText(
      groups = text.groups.zipWithIndex.map((group, index) => group.copy(isComplete = complete.contains(index))),
      candidates = Option.when(complete.nonEmpty)(complete.map(index => hits.getOrElse(index, Nil).toSet).reduce(_ intersect _))
    )

  /**
   * A flat page: the DAO returns the rows, the continuation cursor is assembled here ([[cursorAt]]). Sorted by capture time the
   * search is ordered by day first, and the cursor carries the last image's day.
   */
  def search(query: SearchQuery, scopeFingerprint: String): SearchResult =
    val started = System.currentTimeMillis
    val page = txManager.asReadOnly {
      searchDao.search(query)
    }

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      cursorAt(query, scopeFingerprint, last.day.map(_.toString), None, last.asset, last.sortValue, last.secondSortValue)
    }
    val result = SearchResult(page.rows.map(_.asset), page.total, nextCursor, query.rpp, query.searchSort)

    logger.trace(
      s"Search page: ${result.records.length} assets" +
        result.total.map(total => s" of $total matching").getOrElse(" (continued)") +
        s", in ${System.currentTimeMillis - started}ms")
    result

  /**
   * The cursor that continues a search after the image at this position. It carries the scope fingerprint of the search as
   * requested and, under the Relevance sort, which orders by the capture time next, that too.
   */
  private def cursorAt(
      query: SearchQuery,
      scopeFingerprint: String,
      key: Option[String],
      groupId: Option[String],
      asset: Asset,
      sortValue: SortValue,
      secondSortValue: SortValue): SearchCursor =
    SearchCursor(
      key = key,
      groupId = groupId,
      sortValue = sortValue,
      id = asset.persistedId,
      scope = scopeFingerprint,
      secondSortValue = Option.when(query.searchSort.exists(_.isRelevance))(secondSortValue)
    )

  def count(query: SearchQuery): Int =
    val started = System.currentTimeMillis
    val count = txManager.asReadOnly {
      searchDao.count(query)
    }
    logger.trace(s"Counted $count matching assets in ${System.currentTimeMillis - started}ms")
    count

  def countByFolder(): Map[String, Int] =
    txManager.asReadOnly {
      searchDao.countByFolder()
    }

  def cappedCount(query: SearchQuery): Int =
    val started = System.currentTimeMillis
    val count = txManager.asReadOnly {
      searchDao.cappedCount(query)
    }
    logger.trace(s"Counted $count matching assets, capped at ${query.totalCap}, in ${System.currentTimeMillis - started}ms")
    count

  /** What the map draws for a viewport at a zoom: the cells over the plotted points in the box, and the Locations pinned in it */
  def mapCells(query: SearchQuery, bbox: BoundingBox, zoom: Int): MapCells =
    val started = System.currentTimeMillis
    // Both aggregates read one snapshot
    val result = txManager.asReadOnly {
      MapCells(
        cells = searchDao.mapCells(query, bbox, SearchService.cellDegrees(zoom)),
        locations = searchDao.mapLocations(query, bbox))
    }
    logger.trace(
      s"Map at zoom $zoom in $bbox: ${result.cells.length} cells, ${result.locations.length} Locations, " +
        s"in ${System.currentTimeMillis - started}ms")
    result

  /** The box around every point the search plots, for fitting the map to a result; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds] =
    val started = System.currentTimeMillis
    val bounds = txManager.asReadOnly {
      searchDao.mapBounds(query)
    }
    logger.trace(s"Map bounds ${bounds.getOrElse("of nothing plotted")}, in ${System.currentTimeMillis - started}ms")
    bounds

  /**
   * A grouped page: the DAO returns the rows and counts, the groups and the continuation cursor are assembled here. The cursor
   * points at the last returned image ([[cursorAt]]).
   */
  def searchGrouped(query: SearchQuery, scopeFingerprint: String): GroupedSearchResult =
    val started = System.currentTimeMillis
    val page = txManager.asReadOnly {
      searchDao.searchGrouped(query)
    }

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      cursorAt(
        query,
        scopeFingerprint,
        last.group.cursorKey,
        last.group.cursorGroupId,
        last.asset,
        last.sortValue,
        last.secondSortValue)
    }

    val groups = GroupedSearchResult.groupsOf(page.rows)
    val result = GroupedSearchResult(
      groups = groups,
      total = page.total,
      grouping = query.grouping.get,
      sort = query.searchSort.head,
      nextCursor = nextCursor,
      continuesGroup = query.cursor.exists(cursor => groups.headOption.exists(_.key.continues(cursor)))
    )

    logger.trace(
      s"Grouped search by ${result.grouping.by} ${result.grouping.direction}, sorted ${result.sort}: " +
        s"${result.assets.length} images in ${result.groups.length} groups" +
        result.total.map(total => s" of $total matching").getOrElse(" (continued)") +
        s", in ${System.currentTimeMillis - started}ms")

    result

  /**
   * Indexes a value just added to the asset, of any field type: a metadata parameter when the type is faceted, and the asset's
   * Search document rewritten from the asset as given, which must already carry the value
   */
  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    txManager.withTransaction {
      searchDao.addMetadataValue(asset, field, value)
    }
