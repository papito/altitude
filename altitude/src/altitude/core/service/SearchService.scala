package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

import altitude.core.Altitude
import altitude.core.dao.SearchDao
import altitude.core.models.Asset
import altitude.core.models.FieldType
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

object SearchService:
  private val NON_FACETED_FIELD_TYPES: Set[FieldType] = Set(FieldType.TEXT)

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
    logger.info(s"Indexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.indexAsset(asset, metadataFields)
    }

  def reindexAsset(asset: Asset): Unit =
    logger.info(s"Reindexing asset $asset")
    val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
    searchDao.reindexAsset(asset, metadataFields)

  /**
   * The Search text with each term resolved against the names of the repository's people, Locations, Categories, folders and
   * albums, none of which is stored in a Search document: the candidates of every name source are read in one read-only
   * transaction and matched in memory by their [[SearchWords]], the rule a document is matched by. A Category's matches become
   * the IDs of its Locations, and a folder's the folder with every folder below it, so each source is a plain ID filter for the
   * search that follows.
   */
  def resolveText(expression: SearchExpression): ResolvedSearchText =
    txManager.asReadOnly {
      val started = System.currentTimeMillis
      val names = searchDao.searchNames
      // The words of each name once, however many terms there are
      val words = names.view.mapValues(_.map(name => name.id -> SearchWords.of(name.name))).toMap
      val locationsByCategory = names(SearchSource.Location).groupMap(_.parentId)(_.id)
      val foldersByParent = names(SearchSource.Folder).groupMap(_.parentId)(_.id)

      /** The folders with every folder below them, level by level; a folder already found is never revisited */
      @tailrec def withDescendants(found: Set[String], level: Set[String]): Set[String] =
        val children = level.flatMap(id => foldersByParent.getOrElse(Some(id), Nil)) -- found
        if children.isEmpty then found else withDescendants(found ++ children, children)

      def resolve(term: SearchTerm): ResolvedSearchTerm =
        val hits = words.view.mapValues(_.collect { case (id, nameWords) if term.isIn(nameWords) => id }.toSet).toMap
        val ids = hits
          .updated(SearchSource.Category, hits(SearchSource.Category).flatMap(id => locationsByCategory.getOrElse(Some(id), Nil)))
          .updated(SearchSource.Folder, withDescendants(hits(SearchSource.Folder), hits(SearchSource.Folder)))
        ResolvedSearchTerm(term, ids.filter((_, matching) => matching.nonEmpty))

      val resolved =
        ResolvedSearchText(expression.groups.map(group => ResolvedSearchGroup(group.alternatives.map(resolve))))

      logger.debug(
        s"Resolved the Search text against ${names.values.map(_.size).sum} names in ${System.currentTimeMillis - started}ms: " +
          resolved.groups
            .flatMap(_.alternatives)
            .map(
              term =>
                s"[${term.term.words.mkString(" ")}] ${term.ids.map((source, ids) => s"$source=${ids.size}").mkString(" ")}")
            .mkString(", "))

      resolved
    }

  def search(query: SearchQuery): SearchResult =
    searchDao.search(query)

  def count(query: SearchQuery): Int =
    searchDao.count(query)

  /** What the map draws for a viewport at a zoom: the cells over the plotted points in the box, and the Locations pinned in it */
  def mapCells(query: SearchQuery, bbox: BoundingBox, zoom: Int): MapCells =
    val started = System.currentTimeMillis
    val result = MapCells(
      cells = searchDao.mapCells(query, bbox, SearchService.cellDegrees(zoom)),
      locations = searchDao.mapLocations(query, bbox))
    logger.debug(
      s"Map at zoom $zoom in $bbox: ${result.cells.length} cells, ${result.locations.length} Locations, " +
        s"in ${System.currentTimeMillis - started}ms")
    result

  /** The box around every point the search plots, for fitting the map to a result; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds] =
    searchDao.mapBounds(query)

  /**
   * A grouped page: the DAO returns the rows and counts, the groups and the continuation cursor are assembled here. The cursor
   * points at the last returned image and carries the scope fingerprint of the search as requested; under the Relevance sort,
   * which orders by the capture time next, it carries that too.
   */
  def searchGrouped(query: SearchQuery, scopeFingerprint: String): GroupedSearchResult =
    val started = System.currentTimeMillis
    val page = searchDao.searchGrouped(query)

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      SearchCursor(
        key = last.group.cursorKey,
        groupId = last.group.cursorGroupId,
        sortValue = last.sortValue,
        id = last.asset.persistedId,
        scope = scopeFingerprint,
        secondSortValue = Option.when(query.searchSort.head.isRelevance)(last.secondSortValue)
      )
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

    logger.debug(
      s"Grouped search by ${result.grouping.by} ${result.grouping.direction}, sorted ${result.sort}: " +
        s"${result.assets.length} images in ${result.groups.length} groups" +
        result.total.map(total => s" of $total matching").getOrElse(" (continued)") +
        s", in ${System.currentTimeMillis - started}ms")

    result

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    // some fields are not eligible for parameterized search
    if SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType) then return

    searchDao.addMetadataValue(asset, field, value)

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
    // some fields are not eligible for parameterized search
    if SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType) then return

    searchDao.addMetadataValues(asset, field, values)
