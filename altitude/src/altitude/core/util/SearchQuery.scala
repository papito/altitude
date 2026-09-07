package altitude.core.util

import altitude.core.Api

case class SearchSort(field: String, direction: SortDirection):
  def toJson: ujson.Obj = ujson.Obj(
    Api.Field.SearchSort.DIRECTION -> direction.toString,
    Api.Field.SearchSort.FIELD -> field
  )

  override def toString: String = s"SearchSort(field=$field, direction=${direction.id})"

class SearchQuery(
    val text: Option[String] = None,
    override val params: Map[String, Any] = Map(),
    val metadataFilters: Map[String, Any] = Map(),
    val folderIds: Set[String] = Set(),
    val personIds: Set[String] = Set(),
    val albumIds: Set[String] = Set(),
    rpp: Int = 0,
    page: Int = 1,
    val searchSort: List[SearchSort] = List(),
    val grouping: Option[SearchGrouping] = None,
    val cursor: Option[SearchCursor] = None)
  extends Query(params = metadataFilters, rpp = rpp, page = page):

  if sort.nonEmpty then throw IllegalArgumentException("Cannot use 'sort' in this context - use 'searchSort'")

  if searchSort.size > 1 then throw IllegalArgumentException("Only one sort currently supported")

  // A grouped page is a bounded, fully ordered slice: day, then the sort, then the ID
  if grouping.isDefined && searchSort.isEmpty then throw IllegalArgumentException("A grouped search requires a sort")
  if grouping.isDefined && rpp < 1 then throw IllegalArgumentException("A grouped search requires a page size")

  // A cursor continues a grouped search from a position; the page it yields is the cursor's sequence number, not an offset
  if cursor.isDefined && grouping.isEmpty then throw IllegalArgumentException("A cursor requires a grouped search")
  if cursor.exists(_.nextPage != page) then throw IllegalArgumentException("The page must be the cursor's next page")

  val hasMetadataFilters: Boolean = metadataFilters.nonEmpty
  val isText: Boolean = text.isDefined
  val isGrouped: Boolean = grouping.isDefined
  override val isSorted: Boolean = searchSort.nonEmpty

  override def toString: String =
    s"SearchQuery(text=$text, params: $params, searchSort=${searchSort.headOption}, grouping=$grouping, cursor=${cursor.isDefined}, metadataFilters=$metadataFilters, folderIds=$folderIds, personIds=$personIds, albumIds=$albumIds, rpp=$rpp, page=$page)"

  def add_metadata_filter(_filters: (String, Any)*): SearchQuery =
    copyWith(metadataFilters = metadataFilters ++ _filters)

  def withFolderIds(ids: Set[String]): SearchQuery =
    copyWith(folderIds = ids)

  override def add(_params: (String, Any)*): SearchQuery =
    copyWith(params = params ++ _params)

  // Every copy keeps every other field, so a scoped or filtered copy still describes the same grouped, sorted search
  private def copyWith(
      params: Map[String, Any] = params,
      metadataFilters: Map[String, Any] = metadataFilters,
      folderIds: Set[String] = folderIds): SearchQuery =
    SearchQuery(
      text = text,
      params = params,
      metadataFilters = metadataFilters,
      folderIds = folderIds,
      personIds = personIds,
      albumIds = albumIds,
      rpp = rpp,
      page = page,
      searchSort = searchSort,
      grouping = grouping,
      cursor = cursor
    )
