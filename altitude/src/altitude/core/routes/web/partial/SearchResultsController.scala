package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

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
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId")
  def htmxSearchResults(
      repoId: String,
      view: String = Const.Search.View.DEFAULT,
      rpp: Int = Const.Search.DEFAULT_RPP,
      p: Int = 1,
      q: Option[String] = None,
      sort: String = s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}",
      folderId: Option[String] = None,
      personId: Option[String] = None,
      isContinuousScroll: Boolean = false)(using request: Request): Response[String] =

    // The sort argument is the field name with the direction appended as a single digit, e.g. "filename0"
    val searchSort = SearchSort(
      field = sort.slice(0, sort.length - 1),
      direction = SortDirection(sort.takeRight(1).toInt)
    )

    // Where are we? Triage? Recycle? etc.
    val queryParams: Map[String, Any] = view match
      case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true)
      case Const.Search.View.TRASHBIN =>
        Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false) // recycled but NOT purged
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)

    val searchQuery = new SearchQuery(
      params = queryParams,
      text = q,
      rpp = rpp,
      folderIds = folderId.toSet,
      personIds = personId.toSet,
      page = p,
      searchSort = List(searchSort)
    )

    logger.info(s"QUERY: ${searchQuery.toString}")

    val results = App.altitude.service.library.search(searchQuery)

    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))

    if isJsonFormat then
      val assets = results.records.map(r => r: Asset)
      val jsonPayload = ujson.Obj(
        "ids" -> assets.map(_.persistedId),
        "page" -> p,
        "totalPages" -> results.totalPages
      )
      return cask.Response(ujson.write(jsonPayload), 200, Seq(("Content-Type", "application/json")))

    if isContinuousScroll then
      // no more pages
      if p > results.totalPages then return cask.Response("", 204, Seq(("Content-Type", "text/html")))

      /** This is a request for another page of search results for continuous scroll. */
      val payload = "<!doctype html>" + htmx.html.results_grid(
        results = results,
        p = p,
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
        folderId = folderId
      )
      cask.Response(
        payload,
        200,
        Seq(
          ("Content-Type", "text/html"),
          ("HX-Replace-Url", browserViewUrl(view, sort, q, folderId, personId, request))
        ))

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
      request: Request): String =
    val params = Seq(
      Option.when(view != Const.Search.View.DEFAULT)(Api.Field.Search.VIEW -> view),
      folderId.map(Api.Field.Search.FOLDER_ID -> _),
      personId.map(Api.Field.Search.PERSON_ID -> _),
      q.map(Api.Field.Search.QUERY_TEXT -> _),
      Some(Api.Field.Search.SORT -> sort)
    ).flatten

    App.altitude.service.urlService
      .getBrowserViewUrl(queryParams = params, browserUrl = request.exchange.getRequestHeaders.getFirst("HX-Current-URL"))

  initialize()
