package altitude.core.util

import altitude.core.Api
import altitude.core.Const

case class SearchSort(field: String, direction: SortDirection):

  /** Relevance is the one sort that is not an asset column: it is computed from the Search text */
  def isRelevance: Boolean = field == Const.Search.SORT_RELEVANCE

  def toJson: ujson.Obj = ujson.Obj(
    Api.Field.SearchSort.DIRECTION -> direction.toString,
    Api.Field.SearchSort.FIELD -> field
  )

  override def toString: String = s"SearchSort(field=$field, direction=${direction.id})"

object SearchSort:
  /** By how well an asset matches the Search text, best first */
  val Relevance: SearchSort = SearchSort(Const.Search.SORT_RELEVANCE, SortDirection.DESC)

class SearchQuery(
    val text: Option[String] = None,
    override val params: Map[String, Any] = Map(),
    val metadataFilters: Map[String, Any] = Map(),
    val folderIds: Set[String] = Set(),
    val personIds: Set[String] = Set(),
    val albumIds: Set[String] = Set(),
    val locationIds: Set[String] = Set(),
    val bbox: Option[BoundingBox] = None,
    rpp: Int = 0,
    val searchSort: List[SearchSort] = List(),
    val grouping: Option[SearchGrouping] = None,
    val cursor: Option[SearchCursor] = None,
    val resolvedText: Option[ResolvedSearchText] = None,
    // How far a first page's total counts: one past it means more. With textProbeLimit, tuning carried here so that a test can
    // reach it, and not part of what identifies the search to a cursor.
    val totalCap: Int = Const.Search.TOTAL_CAP,
    // How many hits a group of the Search text may have and still be answered from them (SearchService.resolveText)
    val textProbeLimit: Int = Const.Search.TEXT_PROBE_LIMIT)
  extends Query(params = metadataFilters, rpp = rpp):

  if sort.nonEmpty then throw IllegalArgumentException("Cannot use 'sort' in this context - use 'searchSort'")

  if searchSort.size > 1 then throw IllegalArgumentException("Only one sort currently supported")

  // A grouped page is a bounded, fully ordered slice: its group, then the sort, then the ID
  if grouping.isDefined && searchSort.isEmpty then throw IllegalArgumentException("A grouped search requires a sort")
  if grouping.isDefined && rpp < 1 then throw IllegalArgumentException("A grouped search requires a page size")

  val hasMetadataFilters: Boolean = metadataFilters.nonEmpty

  /**
   * The Search text parsed; None when nothing was typed or what was typed has no usable term. Lazy, so a query that is only
   * copied (a folder scope, an added filter) is not parsed.
   */
  lazy val textExpression: Option[SearchExpression] = text.flatMap(SearchText.parse)
  def isText: Boolean = textExpression.isDefined

  // Relevance is computed from the text's terms: without one there is nothing to rank by
  if searchSort.exists(_.isRelevance) && !isText then throw IllegalArgumentException("The Relevance sort requires Search text")

  /**
   * What a search matches text by: the parsed text with its terms resolved against the repository's names
   * (`SearchService.resolveText`). `text` stays the text as typed, which is what identifies the search to a cursor. A query with
   * text has to be resolved before it reaches the DAO, as its folder scope is; an unresolved one is an error, not a search that
   * silently ignores the names.
   */
  def requireResolvedText: ResolvedSearchText =
    resolvedText.getOrElse(throw IllegalStateException("The Search text was not resolved against the repository's names"))

  val isGrouped: Boolean = grouping.isDefined
  override val isSorted: Boolean = searchSort.nonEmpty

  override def toString: String =
    s"SearchQuery(text=$text, params: $params, searchSort=${searchSort.headOption}, grouping=$grouping, cursor=${cursor.isDefined}, metadataFilters=$metadataFilters, folderIds=$folderIds, personIds=$personIds, albumIds=$albumIds, locationIds=$locationIds, bbox=$bbox, rpp=$rpp)"

  def add_metadata_filter(_filters: (String, Any)*): SearchQuery =
    copyWith(metadataFilters = metadataFilters ++ _filters)

  def withFolderIds(ids: Set[String]): SearchQuery =
    copyWith(folderIds = ids)

  def withResolvedText(resolved: ResolvedSearchText): SearchQuery =
    copyWith(resolvedText = Some(resolved))

  def withTextProbeLimit(limit: Int): SearchQuery =
    copyWith(textProbeLimit = limit)

  override def add(_params: (String, Any)*): SearchQuery =
    copyWith(params = params ++ _params)

  // Every copy keeps every other field, so a scoped or filtered copy still describes the same grouped, sorted search
  private def copyWith(
      params: Map[String, Any] = params,
      metadataFilters: Map[String, Any] = metadataFilters,
      folderIds: Set[String] = folderIds,
      resolvedText: Option[ResolvedSearchText] = resolvedText,
      textProbeLimit: Int = textProbeLimit): SearchQuery =
    SearchQuery(
      text = text,
      params = params,
      metadataFilters = metadataFilters,
      folderIds = folderIds,
      personIds = personIds,
      albumIds = albumIds,
      locationIds = locationIds,
      bbox = bbox,
      rpp = rpp,
      searchSort = searchSort,
      grouping = grouping,
      cursor = cursor,
      resolvedText = resolvedText,
      totalCap = totalCap,
      textProbeLimit = textProbeLimit
    )
