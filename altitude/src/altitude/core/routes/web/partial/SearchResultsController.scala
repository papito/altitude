package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

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
import altitude.core.util.GroupedSearchResult
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
   * With `groupBy`, the grid is grouped by date: a header opens each day, and the page is continued by the `after` cursor the
   * last cell carries (`data-app-search-after`), never by page number. Grouped results are HTML only; the detail modal walks the
   * grid itself. `parseGroupedQuery` validates the request.
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
      if isJsonFormat then return badRequest("Grouped results are available as HTML only", isJsonFormat)

      val searchQuery =
        parseGroupedQuery(
          queryParams,
          rpp,
          p,
          q,
          sort,
          folderId,
          personId,
          albumId,
          groupBy,
          groupDirection,
          after,
          isContinuousScroll) match
          case Left(message) => return badRequest(message, isJsonFormat)
          case Right(query) => query

      logger.info(s"GROUPED QUERY: ${searchQuery.toString}")

      val results: GroupedSearchResult =
        try App.altitude.service.library.searchGrouped(searchQuery)
        catch case ex: SearchCursorException => return badRequest(ex.getMessage, isJsonFormat)

      if isContinuousScroll then
        // The continuation ran dry: results are live, and the images past the cursor may be gone by now
        if results.isEmpty then return noContent

        return html(htmx.html.results_grid_grouped(results = results, isContinuousScroll = true))

      return html(
        includes.html.search_results(
          total = results.total.getOrElse(0),
          sort = results.sort,
          grouping = Some(results.grouping),
          grid = htmx.html.results_grid_grouped(results = results),
          person = personOf(personId),
          view = view,
          folderId = folderId,
          albumId = albumId
        ),
        ("HX-Replace-Url", browserViewUrl(view, sort, q, folderId, personId, albumId, groupBy, groupDirection, request))
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
      if page > results.totalPages then return noContent

      /** This is a request for another page of search results for continuous scroll. */
      return html(htmx.html.results_grid(results = results, p = page, isContinuousScroll = true))

    /**
     * This is a new request (first page) for search results.
     *
     * We may need to add more entities, depending on what is needed, for example if it's a person view.
     */
    html(
      includes.html.search_results(
        total = results.total,
        sort = searchSort,
        grouping = None,
        grid = htmx.html.results_grid(isContinuousScroll = false, p = page, results = results),
        person = personOf(personId),
        view = view,
        folderId = folderId,
        albumId = albumId
      ),
      (
        "HX-Replace-Url",
        browserViewUrl(view, sort, q, folderId, personId, albumId, groupBy = None, groupDirection = None, request))
    )

  /**
   * A grouped request, validated up front. A problem is the message of a 400:
   *   - `groupBy` is `dateTaken` or `dateImported`; `groupDirection` (`asc`/`desc`, default `desc`) and `after` need it
   *   - `sort` is one of the results UI's fields with a direction digit; `rpp` is 1 to the grouped maximum
   *   - `after` is the cursor of the previous page, sent with `isContinuousScroll`; `p` has no meaning in a grouped search
   */
  private def parseGroupedQuery(
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
      after: Option[String],
      isContinuousScroll: Boolean): Either[String, SearchQuery] =

    val by: GroupBy = groupBy.map(GroupBy.fromApiValue) match
      case None => return Left(s"${Api.Field.Search.GROUP_BY} is required")
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.GROUP_BY} value")
      case Some(Some(by)) => by

    val direction: SortDirection = groupDirection.map(parseDirection) match
      case None => SortDirection.DESC
      case Some(None) => return Left(s"${Api.Field.Search.GROUP_DIRECTION} must be asc or desc")
      case Some(Some(direction)) => direction

    val searchSort = parseSort(sort) match
      case None => return Left("Unknown sort")
      case Some(searchSort) => searchSort

    if rpp < 1 || rpp > Const.Search.MAX_GROUPED_RPP then
      return Left(s"${Api.Field.Search.RESULTS_PER_PAGE} must be between 1 and ${Const.Search.MAX_GROUPED_RPP}")
    if p.isDefined then
      return Left(s"${Api.Field.Search.PAGE} is not used by a grouped search, which is continued with ${Api.Field.Search.AFTER}")

    val cursor: Option[SearchCursor] =
      try after.map(SearchCursor.decode)
      catch case ex: SearchCursorException => return Left(ex.getMessage)

    if cursor.isDefined && !isContinuousScroll then
      return Left(s"${Api.Field.Search.AFTER} continues the results: send it with ${Api.Field.Search.IS_CONTINUOUS_SCROLL}")

    Right(
      new SearchQuery(
        params = queryParams,
        text = q,
        rpp = rpp,
        folderIds = folderId.toSet,
        personIds = personId.toSet,
        albumIds = albumId.toSet,
        searchSort = List(searchSort),
        grouping = Some(SearchGrouping(by, direction)),
        cursor = cursor
      ))

  private def parseDirection(value: String): Option[SortDirection] =
    SortDirection.values.find(_.toString.equalsIgnoreCase(value))

  /** The sort argument is the field name with the direction appended as a single digit, e.g. "filename0" */
  private def parseSort(sort: String): Option[SearchSort] =
    val field = sort.dropRight(1)
    Try(SortDirection(sort.takeRight(1).toInt)).toOption
      .filter(_ => Const.Search.SORT_FIELDS.contains(field))
      .map(direction => SearchSort(field = field, direction = direction))

  /** A 400 in the format of the request: `{"error": ...}` for JSON, the plain message for HTML (the snackbar reports the status) */
  private def badRequest(message: String, isJsonFormat: Boolean): Response[String] =
    if isJsonFormat then cask.Response(ujson.write(ujson.Obj("error" -> message)), 400, Seq(("Content-Type", "application/json")))
    else cask.Response(message, 400, Seq(("Content-Type", "text/plain")))

  private def html(payload: Html, headers: (String, String)*): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, ("Content-Type", "text/html") +: headers)

  private def noContent: Response[String] = cask.Response("", 204, Seq(("Content-Type", "text/html")))

  private def personOf(personId: Option[String]): Person =
    personId.map(id => App.altitude.service.person.getById(id): Person).orNull

  /**
   * The bookmarkable URL for this search. Only parameters that are not at their default are included, and always in the same
   * order, so the same search always yields the same URL. `p`, `rpp` and `after` are left out on purpose - a shared link opens at
   * the first page.
   */
  private def browserViewUrl(
      view: String,
      sort: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      groupBy: Option[String],
      groupDirection: Option[String],
      request: Request): String =
    val params = Seq(
      Option.when(view != Const.Search.View.DEFAULT)(Api.Field.Search.VIEW -> view),
      folderId.map(Api.Field.Search.FOLDER_ID -> _),
      personId.map(Api.Field.Search.PERSON_ID -> _),
      albumId.map(Api.Field.Search.ALBUM_ID -> _),
      q.map(Api.Field.Search.QUERY_TEXT -> _),
      Some(Api.Field.Search.SORT -> sort),
      groupBy.map(Api.Field.Search.GROUP_BY -> _),
      groupDirection.map(Api.Field.Search.GROUP_DIRECTION -> _)
    ).flatten

    App.altitude.service.urlService
      .getBrowserViewUrl(queryParams = params, browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL"))

  initialize()
