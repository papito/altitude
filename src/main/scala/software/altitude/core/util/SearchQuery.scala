package software.altitude.core.util

import play.api.libs.json.JsObject
import play.api.libs.json.Json

import software.altitude.core.Api
import software.altitude.core.models.UserMetadataField

case class SearchSort(field: UserMetadataField, direction: SortDirection.Value) {
  def toJson: JsObject = Json.obj(
    Api.Field.SearchSort.DIRECTION -> direction.toString,
    Api.Field.SearchSort.FIELD -> field.toJson
  )
}

class SearchQuery(
    val text: Option[String] = None,
    params: Map[String, Any] = Map(),
    val folderIds: Set[String] = Set(),
    val personIds: Set[String] = Set(),
    rpp: Int = 0,
    page: Int = 1,
    val searchSort: List[SearchSort] = List())
  extends Query(params = params, rpp = rpp, page = page) {

  if (sort.nonEmpty) {
    throw new IllegalArgumentException("Cannot use 'sort' in this context - use 'searchSort'")
  }

  if (searchSort.size > 1) {
    throw new IllegalArgumentException("Only one sort currently supported'")
  }

  val isParameterized: Boolean = params.nonEmpty
  val isText: Boolean = text.nonEmpty
  override val isSorted: Boolean = searchSort.nonEmpty

  override def add(_params: (String, Any)*): SearchQuery =
    new SearchQuery(
      text = text,
      folderIds = folderIds,
      personIds = personIds,
      params = params ++ _params,
      rpp = rpp,
      page = page,
      searchSort = searchSort)
}
