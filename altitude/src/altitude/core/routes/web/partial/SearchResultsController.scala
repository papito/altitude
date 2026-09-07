package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.SearchCursorException
import altitude.core.models.Asset
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.GroupBy
import altitude.core.util.IdSearchResult
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection

class SearchResultsController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/search"

  /**
   * Search results for the whole app.
   *
   * The client owns the complete parameter set: the `searchParams` Alpine store holds every parameter and the single funnel in
   * `static/js/search-results/search.js` sends all of them on every request. So this route reads nothing but its own query string -
   * no merging with the browser URL, no "is this a new search" flag.
   *
   * The response still pushes the user-friendly, bookmarkable browser URL via a special HTMX header. That URL is a projection of
   * the parameters we were given; the client never reads it back. The one thing still taken from the browser URL is its "#tab"
   * fragment, so replacing the URL does not switch the explorer tab.
   *
   * With `groupBy`, the response is the grouped JSON contract instead: matching asset IDs (no asset data) with their date groups
   * and counts, continued either by page number or by the `after` cursor of the previous response. See `groupedJson`.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId")
  def htmxSearchResults(
      repoId: String,
      view: String = Const.Search.View.DEFAULT,
      rpp: Int = Const.Search.DEFAULT_RPP,
      p: Option[Int] = None,
      q: Option[String] = None,
      sort: String = s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}",
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      isContinuousScroll: Boolean = false,
      groupBy: Option[String] = None,
      groupDirection: Option[String] = None,
      after: Option[String] = None)(using request: Request): Response[String] =

    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))

    // Where are we? Triage? Recycle? etc.
    val queryParams: Map[String, Any] = view match
      case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true)
      case Const.Search.View.TRASHBIN =>
        Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false) // recycled but NOT purged
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)

    if groupBy.isDefined || groupDirection.isDefined || after.isDefined then
      if !isJsonFormat then return badRequest("Grouped results are available as JSON only")

      return groupedJson(
        queryParams = queryParams,
        rpp = rpp,
        p = p,
        q = q,
        sort = sort,
        folderId = folderId,
        personId = personId,
        albumId = albumId,
        groupBy = groupBy,
        groupDirection = groupDirection,
        after = after
      )

    val page = p.getOrElse(1)

    // The sort argument is the field name with the direction appended as a single digit, e.g. "filename0"
    val searchSort = SearchSort(
      field = sort.slice(0, sort.length - 1),
      direction = SortDirection(sort.takeRight(1).toInt)
    )

    val searchQuery = new SearchQuery(
      params = queryParams,
      text = q,
      rpp = rpp,
      folderIds = folderId.toSet,
      personIds = personId.toSet,
      albumIds = albumId.toSet,
      page = page,
      searchSort = List(searchSort)
    )

    logger.info(s"QUERY: ${searchQuery.toString}")

    val results = App.altitude.service.library.search(searchQuery)

    if isJsonFormat then
      val assets = results.records.map(r => r: Asset)
      val jsonPayload = ujson.Obj(
        "ids" -> assets.map(_.persistedId),
        "page" -> page,
        "totalPages" -> results.totalPages
      )
      return cask.Response(ujson.write(jsonPayload), 200, Seq(("Content-Type", "application/json")))

    if isContinuousScroll then
      // no more pages
      if page > results.totalPages then return cask.Response("", 204, Seq(("Content-Type", "text/html")))

      /** This is a request for another page of search results for continuous scroll. */
      val payload = "<!doctype html>" + htmx.html.results_grid(
        results = results,
        p = page,
        isContinuousScroll = true
      )
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    else
      /**
       * This is a new request (first page) for search results.
       *
       * We may need to add more entities, depending on what is needed, for example if it's a person view.
       */
      val maybePerson: Option[Person] = personId.map(id => App.altitude.service.person.getById(id): Person)

      val payload = "<!doctype html>" + includes.html.search_results(
        results = results,
        person = maybePerson.orNull,
        view = view,
        folderId = folderId,
        albumId = albumId
      )
      cask.Response(
        payload,
        200,
        Seq(
          ("Content-Type", "text/html"),
          ("HX-Replace-Url", browserViewUrl(view, sort, q, folderId, personId, albumId, request))
        ))

  /**
   * The grouped JSON contract. Every parameter is validated up front and a problem is a 400 with `{"error": ...}`:
   *   - `groupBy` is `dateTaken` or `dateImported`; `groupDirection` (`asc`/`desc`, default `desc`) and `after` need it
   *   - `sort` is one of the results UI's fields with a direction digit; `rpp` is 1 to the grouped maximum
   *   - `p` is a positive page number, or `after` is the previous response's `nextCursor` (never both)
   *
   * The response is `ids` (a flat list, for the existing navigation), `page`, `total`, `totalPages`, the effective `groupBy` and
   * `groupDirection`, `groups` as contiguous ranges over `ids` with each day's full match count, and `nextCursor` (null at the
   * end). A valid empty page is a 200 with empty lists and accurate totals.
   */
  private def groupedJson(
      queryParams: Map[String, Any],
      rpp: Int,
      p: Option[Int],
      q: Option[String],
      sort: String,
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      groupBy: Option[String],
      groupDirection: Option[String],
      after: Option[String]): Response[String] =

    val grouping: SearchGrouping = groupBy.flatMap(GroupBy.fromApiValue) match
      case None if groupBy.isEmpty => return badRequest(s"${Api.Field.Search.GROUP_BY} is required")
      case None => return badRequest(s"Unknown ${Api.Field.Search.GROUP_BY} value")
      case Some(by) =>
        val direction = groupDirection.map(parseDirection) match
          case None => SortDirection.DESC
          case Some(Some(direction)) => direction
          case Some(None) => return badRequest(s"${Api.Field.Search.GROUP_DIRECTION} must be asc or desc")
        SearchGrouping(by, direction)

    val searchSort = parseSort(sort) match
      case Some(searchSort) => searchSort
      case None => return badRequest("Unknown sort")

    if rpp < 1 || rpp > Const.Search.MAX_GROUPED_RPP then
      return badRequest(s"${Api.Field.Search.RESULTS_PER_PAGE} must be between 1 and ${Const.Search.MAX_GROUPED_RPP}")
    if p.exists(_ < 1) then return badRequest(s"${Api.Field.Search.PAGE} must be a positive page number")
    if after.isDefined && p.isDefined then
      return badRequest(s"Use either ${Api.Field.Search.AFTER} or ${Api.Field.Search.PAGE}, not both")

    val cursor: Option[SearchCursor] =
      try after.map(SearchCursor.decode)
      catch case ex: SearchCursorException => return badRequest(ex.getMessage)

    val searchQuery = new SearchQuery(
      params = queryParams,
      text = q,
      rpp = rpp,
      folderIds = folderId.toSet,
      personIds = personId.toSet,
      albumIds = albumId.toSet,
      page = cursor.map(_.nextPage).orElse(p).getOrElse(1),
      searchSort = List(searchSort),
      grouping = Some(grouping),
      cursor = cursor
    )

    logger.info(s"GROUPED QUERY: ${searchQuery.toString}")

    val result: IdSearchResult =
      try App.altitude.service.library.searchIds(searchQuery)
      catch case ex: SearchCursorException => return badRequest(ex.getMessage)

    cask.Response(ujson.write(toJson(result)), 200, Seq(("Content-Type", "application/json")))

  // Hand-built rather than a model codec: the API is camelCase and the cursor is an opaque token
  private def toJson(result: IdSearchResult): ujson.Obj =
    ujson.Obj(
      "ids" -> result.ids,
      "page" -> result.page,
      "total" -> result.total,
      "totalPages" -> result.totalPages,
      "groupBy" -> result.grouping.by.apiValue,
      "groupDirection" -> result.grouping.direction.toString.toLowerCase,
      "groups" -> ujson.Arr.from(result.groups.map {
        group =>
          ujson.Obj(
            "date" -> group.date.toString,
            "startIndex" -> group.startIndex,
            "length" -> group.length,
            "total" -> group.total)
      }),
      "nextCursor" -> result.nextCursor.map(cursor => ujson.Str(cursor.encode)).getOrElse(ujson.Null)
    )

  private def parseDirection(value: String): Option[SortDirection] =
    SortDirection.values.find(_.toString.equalsIgnoreCase(value))

  /** The sort argument is the field name with the direction appended as a single digit, e.g. "filename0" */
  private def parseSort(sort: String): Option[SearchSort] =
    val field = sort.dropRight(1)
    Try(SortDirection(sort.takeRight(1).toInt)).toOption
      .filter(_ => Const.Search.SORT_FIELDS.contains(field))
      .map(direction => SearchSort(field = field, direction = direction))

  private def badRequest(message: String): Response[String] =
    cask.Response(ujson.write(ujson.Obj("error" -> message)), 400, Seq(("Content-Type", "application/json")))

  /**
   * The bookmarkable URL for this search. Only parameters that are not at their default are included, and always in the same
   * order, so the same search always yields the same URL. `p` and `rpp` are left out on purpose - a shared link opens at the
   * first page.
   */
  private def browserViewUrl(
      view: String,
      sort: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      request: Request): String =
    val params = Seq(
      Option.when(view != Const.Search.View.DEFAULT)(Api.Field.Search.VIEW -> view),
      folderId.map(Api.Field.Search.FOLDER_ID -> _),
      personId.map(Api.Field.Search.PERSON_ID -> _),
      albumId.map(Api.Field.Search.ALBUM_ID -> _),
      q.map(Api.Field.Search.QUERY_TEXT -> _),
      Some(Api.Field.Search.SORT -> sort)
    ).flatten

    App.altitude.service.urlService
      .getBrowserViewUrl(queryParams = params, browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL"))

  initialize()
