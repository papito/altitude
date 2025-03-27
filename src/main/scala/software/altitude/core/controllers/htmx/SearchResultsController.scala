package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Person
import software.altitude.core.util.{SearchQuery, SearchSort, SortDirection}

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
    val rpp = params.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = params.getOrElse(Api.Field.Search.PAGE, "1").toInt
    val queryText = params.get(Api.Field.Search.QUERY_TEXT)
    val sortArg = params.getOrElse(Api.Field.Search.SORT, s"${Api.Field.SearchSort.BY_ASSET_CREATED_AT}|${SortDirection.DESC.id}")
    val isContinuousScroll = params.getOrElse(Api.Field.Search.IS_CONTINUOUS_SCROLL, "false").toBoolean
    val folderId = params.get(Api.Field.Search.FOLDER_ID)
    val personId = params.get(Api.Field.Search.PERSON_ID)

    val field :: directionInt :: _ = sortArg.split("\\|").toList
    val sortDirection = SortDirection(directionInt.toInt)
    val sort = SearchSort(field=field, direction=sortDirection)
    logger.debug(s"QUERY: rpp: $rpp, page: $page, sort: $field|$sortDirection, queryText: $queryText, folderIds: $folderId, personIds: $personId")

    val q = new SearchQuery(
      text = queryText,
      rpp = rpp,
      folderIds = folderId.toSet,
      personIds = personId.toSet,
      page = page,
      searchSort = List(sort)
    )

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
      response.addHeader("HX-Replace-Url", app.service.urlService.getBrowserViewUrl(request))

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
