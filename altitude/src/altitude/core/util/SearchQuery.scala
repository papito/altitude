package altitude.core.util

import altitude.core.Api

case class SearchSort(field: String, direction: SortDirection) {
  def toJson: ujson.Obj = ujson.Obj(
    Api.Field.SearchSort.DIRECTION -> direction.toString,
    Api.Field.SearchSort.FIELD -> field
  )

  override def toString: String = s"SearchSort(field=$field, direction=${direction.id})"
}

class SearchQuery(
    val text: Option[String] = None,
    override val params: Map[String, Any] = Map(),
    val metadataFilters: Map[String, Any] = Map(),
    val folderIds: Set[String] = Set(),
    val personIds: Set[String] = Set(),
    rpp: Int = 0,
    page: Int = 1,
    val searchSort: List[SearchSort] = List())
  extends Query(params = metadataFilters, rpp = rpp, page = page) {

  if (sort.nonEmpty) {
    throw new IllegalArgumentException("Cannot use 'sort' in this context - use 'searchSort'")
  }

  if (searchSort.size > 1) {
    throw new IllegalArgumentException("Only one sort currently supported")
  }

  val hasMetadataFilters: Boolean = metadataFilters.nonEmpty
  val isText: Boolean = text.nonEmpty
  override val isSorted: Boolean = searchSort.nonEmpty

  override def toString: String =
    s"SearchQuery(text=$text, params: $params, searchSort=${searchSort.headOption}, metadataFilters=$metadataFilters, folderIds=$folderIds, personIds=$personIds, rpp=$rpp, page=$page)"

  def add_metadata_filter(_filters: (String, Any)*): SearchQuery =
    new SearchQuery(
      text = text,
      folderIds = folderIds,
      personIds = personIds,
      metadataFilters = metadataFilters ++ _filters,
      rpp = rpp,
      page = page,
      searchSort = searchSort
    )

  def withFolderIds(ids: Set[String]): SearchQuery =
    new SearchQuery(
      text = text,
      params = params,
      folderIds = ids,
      personIds = personIds,
      metadataFilters = metadataFilters,
      rpp = rpp,
      page = page,
      searchSort = searchSort
    )

  override def add(_params: (String, Any)*): SearchQuery =
    new SearchQuery(
      text = text,
      params = params ++ _params,
      folderIds = folderIds,
      personIds = personIds,
      metadataFilters = metadataFilters,
      rpp = rpp,
      page = page,
      searchSort = searchSort
    )
}
