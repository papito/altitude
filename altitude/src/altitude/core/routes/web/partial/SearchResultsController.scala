package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.NotFoundException
import altitude.core.QueryTimeoutException
import altitude.core.RequestContext
import altitude.core.SearchCursorException
import altitude.core.models.Folder
import altitude.core.models.Location
import altitude.core.models.Permission
import altitude.core.models.Person
import altitude.core.models.SearchFilters
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.decorators.refusedView
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.GroupBy
import altitude.core.util.GroupedSearchResult
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchGroupKey
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection

class SearchResultsController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/search"

  /**
   * Search results for the whole app. The client sends the complete parameter set on every request, so this route reads only its
   * own query string, never the browser URL. The bookmarkable URL it pushes back (`HX-Replace-Url`) is a projection of those
   * parameters that the client never reads back.
   *
   * A search with a description ranks the still images its other filters match by likeness. Sorted by similarity it is one ranked
   * list ending in the weaker matches; under another sort, or grouped, it is two parts, the matches and then the weaker ones,
   * each sorted and grouped as asked. Either way the page holding the first weaker match has a header before it.
   *
   * Pages are continued by the `after` cursor the last cell carries, never by page number. The map layout renders bounds and a
   * total without fetching rows. A first page's total counts up to `SearchQuery.totalCap`.
   *
   * Results are HTML only: the detail modal walks the rendered grid, so a JSON request is refused.
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
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None,
      bbox: Option[String] = None,
      triaged: Option[Boolean] = None,
      subset: Option[String] = None,
      describe: Option[String] = None,
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

    refusedView(view, triaged) match
      case Some(refusal) => return refusal
      case None =>

    val params = SearchRequestParser.Params(
      view = view,
      q = q,
      folderId = folderId,
      personId = personId,
      albumId = albumId,
      locationId = locationId,
      peopleMatch = peopleMatch,
      takenFrom = takenFrom,
      takenTo = takenTo,
      importedFrom = importedFrom,
      importedTo = importedTo,
      bbox = bbox,
      triaged = triaged,
      subset = subset,
      describe = describe,
      layout = Some(layout)
    )
    val scope = SearchRequestParser.parse(params) match
      case Left(message) => return textError(message, 400)
      case Right(scope) => scope

    if layout != Const.Search.Layout.GRID && layout != Const.Search.Layout.MAP then return textError("Unknown layout", 400)

    // Without a sort: likeness with a description, whatever else it holds, Relevance with Search text, else newest import first
    def defaultSort: String =
      if scope.description.isDefined then Const.Search.SORT_SIMILARITY
      else if scope.text.isDefined then Const.Search.SORT_RELEVANCE
      else s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}"
    val effectiveSort = sort.getOrElse(defaultSort)

    val searchSort = parseSort(effectiveSort) match
      case None => return textError("Unknown sort", 400)
      case Some(searchSort) => searchSort

    if searchSort.isRelevance && scope.text.isEmpty then
      return textError(
        s"${Api.Field.Search.SORT}=${Const.Search.SORT_RELEVANCE} needs Search text (${Api.Field.Search.QUERY_TEXT})",
        400)
    if searchSort.isSimilarity && scope.description.isEmpty then
      return textError(
        s"${Api.Field.Search.SORT}=${Const.Search.SORT_SIMILARITY} needs a description (${Api.Field.Search.DESCRIBE})",
        400)

    // What the results title names. A filtered search's chips are read for a first page alone, since a continuation renders no
    // title, and an ID that names nothing in the repository matches nothing and has no chip. Any other search names one scope at
    // most, read by ID on every page, and an ID that is not the repository's is a 404
    val isFilteredSearch = isFiltered(scope)
    val filters = Option.when(isFilteredSearch && !isContinuousScroll)(filtersOf(scope))
    val (person, folderPath, album, location) =
      if isFilteredSearch then (null, Nil, None, None)
      else
        try
          (
            personOf(scope.personIds.headOption),
            folderPathOf(folderId),
            albumId.map(App.altitude.service.album.getById),
            locationOf(scope.locationIds.headOption))
        catch case ex: NotFoundException => return textError(ex.getMessage, 404)

    val browserUrl = browserViewUrl(
      params,
      effectiveSort,
      layout,
      if layout == Const.Search.Layout.MAP then None else groupBy,
      if layout == Const.Search.Layout.MAP then None else groupDirection,
      request
    )

    if layout == Const.Search.Layout.MAP then
      // In map layout `bbox` scopes only the crowded-pin panel (the URL keeps it): the map itself plots the whole search
      val query = scope.copy(bbox = None).query()
      logger.trace(s"MAP QUERY: $query")
      val library = App.altitude.service.library
      val (total, bounds) =
        try (library.cappedCount(query), library.mapBounds(query))
        catch case _: QueryTimeoutException => return timedOut
      val settings = App.altitude.settings.current
      return htmlResponse(
        includes.html.search_results(
          total = total,
          totalCap = query.totalCap,
          sort = searchSort,
          grouping = None,
          grid = htmx.html.map_view(bounds, settings.mapTileUrl, settings.mapTileAttribution),
          person = person,
          folderPath = folderPath,
          album = album,
          location = location,
          subset = scope.subset,
          filters = filters,
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

    // Zero is refused too, since to a flat search it means no limit
    if rpp < 1 || rpp > Const.Search.MAX_RPP then
      return textError(s"${Api.Field.Search.RESULTS_PER_PAGE} must be between 1 and ${Const.Search.MAX_RPP}", 400)

    val cursor = parseCursor(after, isContinuousScroll) match
      case Left(message) => return textError(message, 400)
      case Right(cursor) => cursor

    val grouping = parseGrouping(view, groupBy, groupDirection) match
      case Left(message) => return textError(message, 400)
      case Right(grouping) => grouping

    if grouping.isDefined then
      val searchQuery = scope.query(rpp = rpp, searchSort = List(searchSort), grouping = grouping, cursor = cursor)

      logger.trace(s"GROUPED QUERY: ${searchQuery.toString}")

      val results: GroupedSearchResult =
        try
          scope.description match
            case Some(description) => App.altitude.service.search.describeGrouped(description, searchQuery)
            case None => App.altitude.service.library.searchGrouped(searchQuery)
        catch
          case ex: SearchCursorException => return textError(ex.getMessage, 400)
          case _: QueryTimeoutException => return timedOut

      // With Curate own alone, a Sequence's Curate counts only the account's own frames, as Curation would show them
      val sequenceIds = results.groups.map(_.key).collect { case SearchGroupKey.Sequence(Some(id), _) => id }.toSet
      val curatable =
        if sequenceIds.isEmpty || !RequestContext.holds(Permission.CurateOwn, Permission.CurateAll) then None
        else App.altitude.service.curation.curatableFrameCounts(sequenceIds)

      if isContinuousScroll then
        // The continuation ran dry: results are live, and the images past the cursor may be gone by now
        if results.isEmpty then return noContentResponse

        return htmlResponse(htmx.html.results_grid_grouped(results = results, isContinuousScroll = true, curatable = curatable))

      return htmlResponse(
        includes.html.search_results(
          total = results.total.getOrElse(0),
          totalCap = searchQuery.totalCap,
          sort = results.sort,
          grouping = Some(results.grouping),
          grid = htmx.html.results_grid_grouped(results = results, curatable = curatable),
          person = person,
          folderPath = folderPath,
          album = album,
          location = location,
          subset = scope.subset,
          filters = filters,
          view = view,
          folderId = folderId,
          albumId = albumId,
          locationId = locationId,
          bbox = bbox,
          text = scope.text
        ),
        ("HX-Replace-Url", browserUrl)
      )

    // The similarity order is the Describe search's own ranking, which the query does not name
    val searchQuery =
      scope.query(rpp = rpp, searchSort = if searchSort.isSimilarity then Nil else List(searchSort), cursor = cursor)
    logger.trace(s"${if scope.description.isDefined then "DESCRIBED " else ""}QUERY: ${searchQuery.toString}")
    val results =
      try
        scope.description match
          case Some(description) => App.altitude.service.search.describe(description, searchQuery)
          case None => App.altitude.service.library.search(searchQuery)
      catch
        case ex: SearchCursorException => return textError(ex.getMessage, 400)
        case _: QueryTimeoutException => return timedOut

    if isContinuousScroll then
      // The continuation ran dry: results are live, and the images past the cursor may be gone by now
      if results.isEmpty then return noContentResponse

      return htmlResponse(htmx.html.results_grid(results = results, isContinuousScroll = true))

    htmlResponse(
      includes.html.search_results(
        total = results.total.getOrElse(0),
        // A Describe search's total is exact: its result limit keeps what it ranked at the cap or under
        totalCap = searchQuery.totalCap,
        sort = searchSort,
        grouping = None,
        grid = htmx.html.results_grid(isContinuousScroll = false, results = results),
        person = person,
        folderPath = folderPath,
        album = album,
        location = location,
        subset = scope.subset,
        filters = filters,
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
   * `groupDirection` applies to date grouping alone. Only a frame in triage carries a Sequence, so `sequence` outside the Triage
   * view is no grouping rather than a 400: the client keeps asking for it across a change of view, so a return to Triage groups
   * by Sequence again.
   */
  private def parseGrouping(
      view: String,
      groupBy: Option[String],
      groupDirection: Option[String]): Either[String, Option[SearchGrouping]] =

    if groupBy.isEmpty && groupDirection.isEmpty then return Right(None)

    val by: GroupBy = groupBy.map(GroupBy.fromApiValue) match
      case None => return Left(s"${Api.Field.Search.GROUP_BY} is required")
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.GROUP_BY} value")
      case Some(Some(by)) => by

    val direction: SortDirection = groupDirection.map(parseDirection) match
      case None => SortDirection.DESC
      case Some(None) => return Left(s"${Api.Field.Search.GROUP_DIRECTION} must be asc or desc")
      case Some(Some(_)) if by.dateField.isEmpty =>
        return Left(s"${Api.Field.Search.GROUP_DIRECTION} does not apply to ${by.apiValue}: the order is fixed")
      case Some(Some(direction)) => direction

    Right(Option.unless(by == GroupBy.Sequence && view != Const.Search.View.TRIAGE)(SearchGrouping(by, direction)))

  /** `after` is valid only with `isContinuousScroll`; without it the request is for a first page */
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

  /** A sort field with a one-digit direction, e.g. "filename0", or "relevance" or "similarity", which are best match first */
  private def parseSort(sort: String): Option[SearchSort] =
    if sort == Const.Search.SORT_RELEVANCE then return Some(SearchSort.Relevance)
    if sort == Const.Search.SORT_SIMILARITY then return Some(SearchSort.Similarity)

    val field = sort.dropRight(1)
    Try(SortDirection(sort.takeRight(1).toInt)).toOption
      .filter(_ => Const.Search.SORT_FIELDS.contains(field))
      .map(direction => SearchSort(field = field, direction = direction))

  private def timedOut: Response[String] =
    textError(Const.Msg.Err.SEARCH_TIMED_OUT, 503)

  /**
   * A filtered search is titled by its filters rather than one scope. The root folder is the whole repository, so it is no folder
   * here.
   */
  private def isFiltered(scope: SearchRequestParser.Scope): Boolean =
    val folders = App.altitude.service.folder
    val scopes = List(
      scope.text.isDefined,
      scope.folderIds.exists(!folders.isRootFolder(_)),
      scope.personIds.nonEmpty,
      scope.locationIds.nonEmpty)
    scope.description.isDefined || scope.takenRange.isDefined || scope.importedRange.isDefined || scope.personIds.size > 1 ||
    scope.locationIds.size > 1 || scopes.count(identity) > 1

  /** The people and the Locations keep the request's order */
  private def filtersOf(scope: SearchRequestParser.Scope): SearchFilters =
    SearchFilters(
      description = scope.description,
      text = scope.text,
      names = App.altitude.service.search.filterNames(scope.folderIds.headOption, scope.personIds, scope.locationIds),
      peopleMatch = scope.peopleMatch,
      takenRange = scope.takenRange,
      importedRange = scope.importedRange
    )

  private def personOf(personId: Option[String]): Person =
    personId.map(App.altitude.service.person.getById).orNull

  /** The folder scope as a breadcrumb, top-level folder first; the repository root has no path */
  private def folderPathOf(folderId: Option[String]): List[Folder] =
    val folders = App.altitude.service.folder
    folderId.filterNot(folders.isRootFolder).map(id => folders.getAncestors(id) :+ folders.getById(id)).getOrElse(Nil)

  /** With its category's name, which the typed read leaves out */
  private def locationOf(locationId: Option[String]): Option[Location] =
    locationId.map {
      id =>
        val locations = App.altitude.service.location
        val location = locations.getById(id)
        location.copy(categoryName = location.categoryId.map(locations.getById(_).name))
    }

  /**
   * The bookmarkable URL of this search, its parameters in a fixed order so one search always yields one URL. `rpp` and `after`
   * are left out so a shared link opens at the first page.
   */
  private def browserViewUrl(
      search: SearchRequestParser.Params,
      sort: String,
      layout: String,
      groupBy: Option[String],
      groupDirection: Option[String],
      request: Request): String =
    val params = Seq(
      Option.when(search.view != Const.Search.View.DEFAULT)(Api.Field.Search.VIEW -> search.view),
      search.folderId.map(Api.Field.Search.FOLDER_ID -> _),
      search.personId.map(Api.Field.Search.PERSON_ID -> _),
      search.albumId.map(Api.Field.Search.ALBUM_ID -> _),
      search.locationId.map(Api.Field.Search.LOCATION_ID -> _),
      search.peopleMatch.map(Api.Field.Search.PEOPLE_MATCH -> _),
      search.takenFrom.map(Api.Field.Search.TAKEN_FROM -> _),
      search.takenTo.map(Api.Field.Search.TAKEN_TO -> _),
      search.importedFrom.map(Api.Field.Search.IMPORTED_FROM -> _),
      search.importedTo.map(Api.Field.Search.IMPORTED_TO -> _),
      search.subset.map(Api.Field.Search.SUBSET -> _),
      search.bbox.map(Api.Field.Search.BBOX -> _),
      search.triaged.map(Api.Field.Search.TRIAGED -> _.toString),
      Option.when(layout != Const.Search.Layout.GRID)(Api.Field.Search.LAYOUT -> layout),
      search.q.map(Api.Field.Search.QUERY_TEXT -> _),
      search.describe.map(Api.Field.Search.DESCRIBE -> _.strip),
      Some(Api.Field.Search.SORT -> sort),
      groupBy.map(Api.Field.Search.GROUP_BY -> _),
      groupDirection.map(Api.Field.Search.GROUP_DIRECTION -> _)
    ).flatten

    App.altitude.service.urlService
      .getBrowserViewUrl(queryParams = params, browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL"))

  initialize()
