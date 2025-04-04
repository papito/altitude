package software.altitude.core.controllers.htmx

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.scalatra.Route
import software.altitude.core.{Api, Const, FieldConst}
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Person
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchSort
import software.altitude.core.util.SortDirection

class SearchResultsController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val htmxSearchResults: Route = get("/r/:repoId") {
    search()
  }

  put("/r/:repoId") {
    search()
  }

  private def search() = {

    /**
     * The search controller combines the query parameters from the browser URL and the HTMX request.
     *
     * The browser URL is used to get the current state of the search, and the HTMX request is used to get the new search
     * parameters.
     *
     * For example, if the user is viewing a specific person, the browser URL will contain the person ID, but when the user
     * selection an option in, say, the sorting widget, the sorting widget is not aware of the other query parameters, so it will
     * only send the sorting parameter.
     *
     * Combining the two allows us to get the full set of query parameters.
     *
     * Note that the new HTMX parameters will override the same URL parameters. So when the browser URL says "ascending sort" and
     * the HTMX request says "descending sort", the HTMX request will take precedence.
     *
     * When this method is done, it will force the new user-friendly browser URL via a special HTMX header.
     */
    val requestQuery = URLDecoder.decode(Option(request.getQueryString).getOrElse(""), StandardCharsets.UTF_8.toString)

    val browserUrl = request.getHeader("HX-Current-URL")
    val browserQuery = new java.net.URI(browserUrl).getQuery

    val htmxQueryParams = app.service.urlService.getUrlParams(requestQuery)
    val browserQueryParams = app.service.urlService.getUrlParams(browserQuery)

    val urlParams = browserQueryParams ++ htmxQueryParams

    // Where are we? Triage? Recycle? etc.
    val view = urlParams.getOrElse(Api.Field.Search.VIEW, Const.Search.View.DEFAULT)
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

    val queryParams: Map[String, Any] = view match {
        case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true)
        case Const.Search.View.RECYCLED => Map(FieldConst.Asset.IS_RECYCLED -> true)
        case _ => Map.empty[String, Any]
    }

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

    val results = app.service.library.search(q)

    if (page > results.totalPages) {
      halt(204)
    }

    if (isContinuousScroll) {

      /** This is a request for another page of search results for continuous scroll. */
      ssp(
        "/htmx/results_grid",
        Api.Field.Search.RESULTS -> results,
        Api.Field.Search.PAGE -> page,
        Api.Field.Search.IS_CONTINUOUS_SCROLL -> true
      )
    } else {

      /**
       * This is a new request (first page) for search results.
       *
       * We may need to add more entities, depending on what is needed, for example if it's a person view.
       */

      // Replace the current browser URL with user-friendly search URL that can be bookmarked or shared
      response.addHeader(
        "HX-Replace-Url",
        app.service.urlService.getBrowserViewUrl(combinedQueryParams = urlParams, browserUrl = browserUrl))

      val maybePerson: Option[Person] = personId.map(app.service.person.getById).map(Person.fromJson)

      ssp(
        "/includes/search_results",
        Api.Field.Search.RESULTS -> results,
        Api.Field.Search.PAGE -> page,
        Api.Field.Search.PERSON -> maybePerson.orNull,
        Api.Field.Search.IS_CONTINUOUS_SCROLL -> false
      )
    }
  }
}
