package altitude.core.util

import altitude.core.Api
import altitude.core.Const
import altitude.core.FieldConst

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
    // Whether the assets hold every one of `personIds` or any of them; it tells nothing apart with fewer than two people
    val peopleMatch: PeopleMatch = PeopleMatch.All,
    val albumIds: Set[String] = Set(),
    val locationIds: Set[String] = Set(),
    // The capture days and the import days the assets fall in; an asset with no capture time is in no Date Taken range
    val takenRange: Option[DateRange] = None,
    val importedRange: Option[DateRange] = None,
    val bbox: Option[BoundingBox] = None,
    // A Triage subset, the assets one of the Triage summary's counts shows: with a Face of an Unknown person, or in no Location
    val subset: Option[TriageSubset] = None,
    rpp: Int = 0,
    val searchSort: List[SearchSort] = List(),
    val grouping: Option[SearchGrouping] = None,
    val cursor: Option[SearchCursor] = None,
    val resolvedText: Option[ResolvedSearchText] = None,
    // How far a first page's total counts: one past it means more. With textProbeLimit, tuning carried here so that a test can
    // reach it, and not part of what identifies the search to a cursor.
    val totalCap: Int = Const.Search.TOTAL_CAP,
    // Whether a first page counts that total at all: off for a caller that counts the matches exactly itself (Quick view), so
    // they are not counted twice. Not part of what identifies the search to a cursor either.
    val countsTotal: Boolean = true,
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

  /** Whether this page counts the total: a first page does, unless the search was asked for without one (`withoutTotal`) */
  def isTotalCounted: Boolean = cursor.isEmpty && countsTotal

  val isGrouped: Boolean = grouping.isDefined
  override val isSorted: Boolean = searchSort.nonEmpty

  override def toString: String =
    s"SearchQuery(text=$text, params: $params, searchSort=${searchSort.headOption}, grouping=$grouping, cursor=${cursor.isDefined}, metadataFilters=$metadataFilters, folderIds=$folderIds, personIds=$personIds, peopleMatch=$peopleMatch, albumIds=$albumIds, locationIds=$locationIds, takenRange=$takenRange, importedRange=$importedRange, subset=$subset, bbox=$bbox, rpp=$rpp)"

  def add_metadata_filter(_filters: (String, Any)*): SearchQuery =
    copyWith(metadataFilters = metadataFilters ++ _filters)

  def withFolderIds(ids: Set[String]): SearchQuery =
    copyWith(folderIds = ids)

  def withResolvedText(resolved: ResolvedSearchText): SearchQuery =
    copyWith(resolvedText = Some(resolved))

  def withTextProbeLimit(limit: Int): SearchQuery =
    copyWith(textProbeLimit = limit)

  /** The same search with no total on its first page */
  def withoutTotal: SearchQuery =
    copyWith(countsTotal = false)

  override def add(_params: (String, Any)*): SearchQuery =
    copyWith(params = params ++ _params)

  // Every copy keeps every other field, so a scoped or filtered copy still describes the same grouped, sorted search
  private def copyWith(
      params: Map[String, Any] = params,
      metadataFilters: Map[String, Any] = metadataFilters,
      folderIds: Set[String] = folderIds,
      resolvedText: Option[ResolvedSearchText] = resolvedText,
      countsTotal: Boolean = countsTotal,
      textProbeLimit: Int = textProbeLimit): SearchQuery =
    SearchQuery(
      text = text,
      params = params,
      metadataFilters = metadataFilters,
      folderIds = folderIds,
      personIds = personIds,
      peopleMatch = peopleMatch,
      albumIds = albumIds,
      locationIds = locationIds,
      takenRange = takenRange,
      importedRange = importedRange,
      bbox = bbox,
      subset = subset,
      rpp = rpp,
      searchSort = searchSort,
      grouping = grouping,
      cursor = cursor,
      resolvedText = resolvedText,
      totalCap = totalCap,
      countsTotal = countsTotal,
      textProbeLimit = textProbeLimit
    )

object SearchQuery:
  /** The Triage view's flags: the triaged assets outside the trash, since an asset recycled from triage keeps its triage flag */
  val TRIAGE_VIEW: Map[String, Any] = Map(FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)
