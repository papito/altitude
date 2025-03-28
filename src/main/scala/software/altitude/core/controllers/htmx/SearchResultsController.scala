package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Person
import software.altitude.core.util.{SearchQuery, SearchSort, SortDirection}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
    val requestQuery = URLDecoder.decode(
      Option(request.getQueryString).getOrElse(""),
      StandardCharsets.UTF_8.toString)
    println("REQ QUERY:", requestQuery)

    val browserUrl = request.getHeader("HX-Current-URL")
    println("BROWSER URL:", browserUrl)
    val browserQuery = new java.net.URI(browserUrl).getQuery
    println("BROWSER QUERY:", browserQuery)

    val htmxQueryParams = app.service.urlService.getUrlParams(requestQuery)
    val browserQueryParams = app.service.urlService.getUrlParams(browserQuery)

    val urlParams = browserQueryParams ++ htmxQueryParams

    println("HTMX URL PARAMS:", htmxQueryParams)
    println("BROWSER URL PARAMS:", browserQueryParams)
    println("URL PARAMS:", urlParams)

    val rpp = urlParams.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = urlParams.getOrElse(Api.Field.Search.PAGE, "1").toInt
    val queryText = urlParams.get(Api.Field.Search.QUERY_TEXT)
    val sortArg = urlParams.getOrElse(Api.Field.Search.SORT, s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}${SortDirection.DESC.id}")
    val isContinuousScroll = urlParams.getOrElse(Api.Field.Search.IS_CONTINUOUS_SCROLL, "false").toBoolean
    val folderId = urlParams.get(Api.Field.Search.FOLDER_ID)
    val personId = urlParams.get(Api.Field.Search.PERSON_ID)

    val sortField = sortArg.slice(0, sortArg.length - 1)
    val sortDirectionInt = sortArg.takeRight(1).toInt
    val sortDirection = SortDirection(sortDirectionInt)
    val sort = SearchSort(field=sortField, direction=sortDirection)

    val q = new SearchQuery(
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
      /**
       * This is a request for another page of search results for continuous scroll.
       */
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
       * We may need to add more entities, depending on what is needed, for example
       * if it's a person view.
       */

      // Replace the current browser URL with user-friendly URL that can be bookmarked or shared
      // (what we have now is the internal HTMX URL)
      response.addHeader("HX-Replace-Url", app.service.urlService.getBrowserViewUrl(
        combinedQueryParams = urlParams, browserUrl=browserUrl))

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
