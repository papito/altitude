package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.QueryTimeoutException
import altitude.core.SearchCursorException
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
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
   * `sort` defaults to Relevance when the request has Search text (`q` with a usable term) and to the newest import first when it
   * has none; `sort=relevance` without text is a 400, as is text with the trash view, which text does not search.
   *
   * With `groupBy`, a header opens each date or Location (`parseGroupedQuery`). Grouped or not, a page is `rpp` assets, 1 to
   * `Const.Search.MAX_RPP`, and is continued by the `after` cursor its last cell carries (`data-app-search-after`), never by page
   * number (`parseCursor`).
   *
   * `layout=map` renders bounds and a total without fetching asset rows; grouping, paging and the `bbox` filter have no effect on
   * them (`bbox` scopes the panel and the grid, and stays in the URL).
   *
   * A first page's total, and the map layout's, counts the matches up to `SearchQuery.totalCap`; the template says when there are
   * more.
   *
   * Results are HTML only: the detail modal walks the rendered grid, so nothing asks for them as JSON, and a JSON request is
   * refused.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId")
  def htmxSearchResults(
      repoId: String,
      view: String = Const.Search.View.DEFAULT,
      rpp: Int = Const.Search.DEFAULT_RPP,
      q: Option[String] = None,
      sort: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None,
      bbox: Option[String] = None,
      layout: String = Const.Search.Layout.GRID,
      isContinuousScroll: Boolean = false,
      groupBy: Option[String] = None,
      groupDirection: Option[String] = None,
      after: Option[String] = None)(using request: Request): Response[String] =

    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))

    if isJsonFormat then
      return cask.Response(
        ujson.write(ujson.Obj("error" -> "Search results are available as HTML only")),
        400,
        Seq(("Content-Type", "application/json")))

    val scope = SearchRequestParser.parse(view, q, folderId, personId, albumId, locationId, bbox) match
      case Left(message) => return badRequest(message)
      case Right(scope) => scope

    if layout != Const.Search.Layout.GRID && layout != Const.Search.Layout.MAP then return badRequest("Unknown layout")

    val effectiveSort = sort.getOrElse(
      if scope.text.isDefined then Const.Search.SORT_RELEVANCE
      else s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}")

    val searchSort = parseSort(effectiveSort) match
      case None => return badRequest("Unknown sort")
      case Some(searchSort) => searchSort

    if searchSort.isRelevance && scope.text.isEmpty then
      return badRequest(
        s"${Api.Field.Search.SORT}=${Const.Search.SORT_RELEVANCE} needs Search text (${Api.Field.Search.QUERY_TEXT})")

    val browserUrl = browserViewUrl(
      view,
      effectiveSort,
      q,
      folderId,
      personId,
      albumId,
      locationId,
      bbox,
      layout,
      if layout == Const.Search.Layout.MAP then None else groupBy,
      if layout == Const.Search.Layout.MAP then None else groupDirection,
      request
    )

    if layout == Const.Search.Layout.MAP then
      // In map layout `bbox` is the crowded-pin panel's scope (the URL keeps it): the map itself plots the whole search
      val query = scope.copy(bbox = None).query()
      logger.trace(s"MAP QUERY: $query")
      val library = App.altitude.service.library
      val (total, bounds) =
        try (library.cappedCount(query), library.mapBounds(query))
        catch case _: QueryTimeoutException => return timedOut
      return html(
        includes.html.search_results(
          total = total,
          totalCap = query.totalCap,
          sort = searchSort,
          grouping = None,
          grid = htmx.html.map_view(
            bounds,
            App.altitude.config.getString(Const.Conf.MAP_TILE_URL),
            App.altitude.config.getString(Const.Conf.MAP_TILE_ATTRIBUTION)),
          person = personOf(personId),
          view = view,
          folderId = folderId,
          albumId = albumId,
          locationId = locationId,
          bbox = bbox,
          layout = layout,
          text = scope.text
        ),
        "HX-Replace-Url" -> browserUrl
      )

    // Every grid page is bounded; zero is refused too, since to a flat search it means no limit, every match
    if rpp < 1 || rpp > Const.Search.MAX_RPP then
      return badRequest(s"${Api.Field.Search.RESULTS_PER_PAGE} must be between 1 and ${Const.Search.MAX_RPP}")

    val cursor = parseCursor(after, isContinuousScroll) match
      case Left(message) => return badRequest(message)
      case Right(cursor) => cursor

    if groupBy.isDefined || groupDirection.isDefined then
      val searchQuery =
        parseGroupedQuery(scope, rpp, searchSort, groupBy, groupDirection, cursor) match
          case Left(message) => return badRequest(message)
          case Right(query) => query

      logger.trace(s"GROUPED QUERY: ${searchQuery.toString}")

      val results: GroupedSearchResult =
        try App.altitude.service.library.searchGrouped(searchQuery)
        catch
          case ex: SearchCursorException => return badRequest(ex.getMessage)
          case _: QueryTimeoutException => return timedOut

      if isContinuousScroll then
        // The continuation ran dry: results are live, and the images past the cursor may be gone by now
        if results.isEmpty then return noContent

        return html(htmx.html.results_grid_grouped(results = results, isContinuousScroll = true))

      return html(
        includes.html.search_results(
          total = results.total.getOrElse(0),
          totalCap = searchQuery.totalCap,
          sort = results.sort,
          grouping = Some(results.grouping),
          grid = htmx.html.results_grid_grouped(results = results),
          person = personOf(personId),
          view = view,
          folderId = folderId,
          albumId = albumId,
          locationId = locationId,
          bbox = bbox,
          text = scope.text
        ),
        ("HX-Replace-Url", browserUrl)
      )

    val searchQuery = scope.query(rpp = rpp, searchSort = List(searchSort), cursor = cursor)

    logger.trace(s"QUERY: ${searchQuery.toString}")

    val results =
      try App.altitude.service.library.search(searchQuery)
      catch
        case ex: SearchCursorException => return badRequest(ex.getMessage)
        case _: QueryTimeoutException => return timedOut

    if isContinuousScroll then
      // The continuation ran dry: results are live, and the images past the cursor may be gone by now
      if results.isEmpty then return noContent

      /** This is a request for another page of search results for continuous scroll. */
      return html(htmx.html.results_grid(results = results, isContinuousScroll = true))

    /**
     * This is a new request (first page) for search results.
     *
     * We may need to add more entities, depending on what is needed, for example if it's a person view.
     */
    html(
      includes.html.search_results(
        total = results.total.getOrElse(0),
        totalCap = searchQuery.totalCap,
        sort = searchSort,
        grouping = None,
        grid = htmx.html.results_grid(isContinuousScroll = false, results = results),
        person = personOf(personId),
        view = view,
        folderId = folderId,
        albumId = albumId,
        locationId = locationId,
        bbox = bbox,
        text = scope.text
      ),
      ("HX-Replace-Url", browserUrl)
    )

  /**
   * A grouped request, validated up front. A problem is the message of a 400:
   *   - `groupBy` is `dateTaken` or `location`; `groupDirection` (`asc`/`desc`, default `desc`) orders the days of `dateTaken`
   *     and is refused with `location`, whose order is fixed
   *   - `sort` was parsed by the caller (`parseSort`), `rpp` checked by it and the cursor decoded by it (`parseCursor`)
   */
  private def parseGroupedQuery(
      scope: SearchRequestParser.Scope,
      rpp: Int,
      searchSort: SearchSort,
      groupBy: Option[String],
      groupDirection: Option[String],
      cursor: Option[SearchCursor]): Either[String, SearchQuery] =

    val by: GroupBy = groupBy.map(GroupBy.fromApiValue) match
      case None => return Left(s"${Api.Field.Search.GROUP_BY} is required")
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.GROUP_BY} value")
      case Some(Some(by)) => by

    val direction: SortDirection = groupDirection.map(parseDirection) match
      case None => SortDirection.DESC
      case Some(None) => return Left(s"${Api.Field.Search.GROUP_DIRECTION} must be asc or desc")
      case Some(Some(_)) if by == GroupBy.Location =>
        return Left(s"${Api.Field.Search.GROUP_DIRECTION} does not apply to ${by.apiValue}: the order is fixed")
      case Some(Some(direction)) => direction

    Right(
      scope.query(
        rpp = rpp,
        searchSort = List(searchSort),
        grouping = Some(SearchGrouping(by, direction)),
        cursor = cursor
      ))

  /**
   * The cursor of the previous page, which `after` carries and which is sent with `isContinuousScroll`, or the message of a 400.
   * Without `after` the request is for a first page.
   */
  private def parseCursor(after: Option[String], isContinuousScroll: Boolean): Either[String, Option[SearchCursor]] =
    val cursor: Option[SearchCursor] =
      try after.map(SearchCursor.decode)
      catch case ex: SearchCursorException => return Left(ex.getMessage)

    Either.cond(
      cursor.isEmpty || isContinuousScroll,
      cursor,
      s"${Api.Field.Search.AFTER} continues the results: send it with ${Api.Field.Search.IS_CONTINUOUS_SCROLL}")

  private def parseDirection(value: String): Option[SortDirection] =
    SortDirection.values.find(_.toString.equalsIgnoreCase(value))

  /**
   * The sort argument is one of the results UI's fields with the direction appended as a single digit, e.g. "filename0", or
   * "relevance" alone, which has one direction: best match first
   */
  private def parseSort(sort: String): Option[SearchSort] =
    if sort == Const.Search.SORT_RELEVANCE then return Some(SearchSort.Relevance)

    val field = sort.dropRight(1)
    Try(SortDirection(sort.takeRight(1).toInt)).toOption
      .filter(_ => Const.Search.SORT_FIELDS.contains(field))
      .map(direction => SearchSort(field = field, direction = direction))

  /** A plain-text 400; the snackbar reports the status, since no visible control is behind a continuation */
  private def badRequest(message: String): Response[String] =
    cask.Response(message, 400, Seq(("Content-Type", "text/plain")))

  /** A plain-text 503 for a search that ran past the engine's time limit for reads */
  private def timedOut: Response[String] =
    cask.Response(Const.Msg.Err.SEARCH_TIMED_OUT, 503, Seq(("Content-Type", "text/plain")))

  private def html(payload: Html, headers: (String, String)*): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, ("Content-Type", "text/html") +: headers)

  private def noContent: Response[String] = cask.Response("", 204, Seq(("Content-Type", "text/html")))

  private def personOf(personId: Option[String]): Person =
    personId.map(id => App.altitude.service.person.getById(id): Person).orNull

  /**
   * The bookmarkable URL for this search. Only parameters that are not at their default are included, and always in the same
   * order, so the same search always yields the same URL. `rpp` and `after` are left out on purpose - a shared link opens at the
   * first page.
   */
  private def browserViewUrl(
      view: String,
      sort: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      locationId: Option[String],
      bbox: Option[String],
      layout: String,
      groupBy: Option[String],
      groupDirection: Option[String],
      request: Request): String =
    val params = Seq(
      Option.when(view != Const.Search.View.DEFAULT)(Api.Field.Search.VIEW -> view),
      folderId.map(Api.Field.Search.FOLDER_ID -> _),
      personId.map(Api.Field.Search.PERSON_ID -> _),
      albumId.map(Api.Field.Search.ALBUM_ID -> _),
      locationId.map(Api.Field.Search.LOCATION_ID -> _),
      bbox.map(Api.Field.Search.BBOX -> _),
      Option.when(layout != Const.Search.Layout.GRID)(Api.Field.Search.LAYOUT -> layout),
      q.map(Api.Field.Search.QUERY_TEXT -> _),
      Some(Api.Field.Search.SORT -> sort),
      groupBy.map(Api.Field.Search.GROUP_BY -> _),
      groupDirection.map(Api.Field.Search.GROUP_DIRECTION -> _)
    ).flatten

    App.altitude.service.urlService
      .getBrowserViewUrl(queryParams = params, browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL"))

  initialize()
