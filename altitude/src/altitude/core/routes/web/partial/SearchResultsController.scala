package altitude.core.routes.web.partial

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.models.Asset
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import cask.Request
import cask.model.Response
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.slf4j.Logger

class SearchResultsController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/search"

  /*
  This is a multi-method route as some modals (people merge) can redirect to it from non-GET requests.
   */
  @requireLogin()
  @cask.route(f"/$prefix/r/:repoId", methods = Seq("get", "put"))
  def htmxSearchResultsGet(
      repoId: String,
      view: Option[String] = None,
      newSearch: Option[String] = None,
      isContinuousScroll: Option[String] = None,
      rpp: Option[String] = None,
      p: Option[String] = None,
      q: Option[String] = None,
      sort: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      parentId: Option[String] = None,
      format: Option[String] = None)(using request: Request): Response[String] =
    /**
     * The search controller combines the query parameters from the browser URL and the HTMX request.
     *
     * The browser URL is used to get the current state of the search, and the HTMX request is used to get the new search
     * parameters.
     *
     * For example, if the user is viewing a specific person, the browser URL will contain the person ID, but when the user
     * selects an option in, say, the sorting widget, the sorting widget is not aware of the other query parameters, so it will
     * only send the sorting parameter.
     *
     * Combining the two allows us to get the full set of query parameters, both from the URL (current state) and from the HTMX
     * request (new state).
     *
     * Note that the new HTMX parameters will override the same URL parameters. So when the browser URL dictates "ascending sort"
     * and the HTMX request has "descending sort", the HTMX request will take precedence as the new value.
     *
     * When this method is finished, it will force the new user-friendly browser URL via a special HTMX header.
     */
    val requestQuery = URLDecoder.decode(Option(request.exchange.getQueryString).getOrElse(""), StandardCharsets.UTF_8.toString)

    val browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL")
    val browserQuery = if browserUrl != null then new java.net.URI(browserUrl).getQuery else null

    val htmxQueryParams = App.altitude.service.urlService.getUrlParams(requestQuery)
    val browserQueryParams = App.altitude.service.urlService.getUrlParams(browserQuery)

    val isNewSearch = htmxQueryParams.getOrElse(Api.Field.Search.IS_NEW_SEARCH, "false").toBoolean

    // If this is a new search, we ignore the browser query params and only use the HTMX params
    val urlParams =
      if isNewSearch then htmxQueryParams
      else browserQueryParams ++ htmxQueryParams

    // Where are we? Triage? Recycle? etc.
    val view = urlParams.getOrElse(Api.Field.Search.VIEW, Const.Search.View.DEFAULT)
    // result per page
    val rpp = urlParams.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = urlParams.getOrElse(Api.Field.Search.PAGE, "1").toInt

    val queryText = urlParams.get(Api.Field.Search.QUERY_TEXT)
    val sortArg =
      urlParams.getOrElse(Api.Field.Search.SORT, s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}")
    val isContinuousScroll = urlParams.getOrElse(Api.Field.Search.IS_CONTINUOUS_SCROLL, "false").toBoolean
    val folderId = urlParams.get(Api.Field.Search.FOLDER_ID)
    val personId = urlParams.get(Api.Field.Search.PERSON_ID)

    val sortField = sortArg.slice(0, sortArg.length - 1)
    val sortDirectionInt = sortArg.takeRight(1).toInt
    val sortDirection = SortDirection(sortDirectionInt)
    val sort = SearchSort(field = sortField, direction = sortDirection)

    val queryParams: Map[String, Any] = view match
      case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true)
      case Const.Search.View.TRASHBIN =>
        Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false) // recycled but NOT purged
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)

    val q = new SearchQuery(
      params = queryParams,
      text = queryText,
      rpp = rpp,
      folderIds = folderId.toSet,
      personIds = personId.toSet,
      page = page,
      searchSort = List(sort)
    )

    logger.info(s"QUERY: ${q.toString}")

    val results = App.altitude.service.library.search(q)

    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))

    if isJsonFormat then
      val assets = results.records.map(r => r: Asset)
      val ids = assets.map(_.persistedId)
      val fileNamesMap = assets.map(a => a.persistedId -> a.fileName).toMap
      val jsonPayload = ujson.Obj(
        "ids" -> ids,
        "page" -> page,
        "totalPages" -> results.totalPages,
      )
      return cask.Response(
        ujson.write(jsonPayload),
        200,
        Seq(("Content-Type", "application/json")))

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

      // Replace the current browser URL with user-friendly search URL that can be bookmarked or shared
      val replaceUrl = App.altitude.service.urlService.getBrowserViewUrl(combinedQueryParams = urlParams, browserUrl = browserUrl)

      val payload = "<!doctype html>" + includes.html.search_results(
        results = results,
        person = maybePerson.orNull,
        view = view
      )
      cask.Response(
        payload,
        200,
        Seq(
          ("Content-Type", "text/html"),
          ("HX-Replace-Url", replaceUrl)
        ))

  initialize()
