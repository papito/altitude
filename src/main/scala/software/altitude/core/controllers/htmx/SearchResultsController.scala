package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Person
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchSort

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
    val sortArg = params.get(Api.Field.Search.SORT)
    val isContinuousScroll = params.getOrElse(Api.Field.Search.IS_CONTINUOUS_SCROLL, "false").toBoolean
    val folderIdsCsv = params.get(Api.Field.Search.FOLDER_IDS)
    val personIdsCsv = params.get(Api.Field.Search.PEOPLE_IDS)

    val folderIds: Set[String] = if (folderIdsCsv.isDefined) {
      folderIdsCsv.get.split(",").toSet
    } else {
      Set()
    }

    val personIds: Set[String] = if (personIdsCsv.isDefined) {
      personIdsCsv.get.split(",").toSet
    } else {
      Set()
    }

    logger.debug(s"QUERY: rpp: $rpp, page: $page, queryText: $queryText, folderIds: $folderIds, personIds: $personIds")

    val sort: List[SearchSort] = if (sortArg.isDefined) {
      val fieldId :: directionInt :: _ = sortArg.get.split("\\|").toList
      logger.info(s"Sort field ID: $fieldId, direction: $directionInt")
      List()
    } else {
      List()
    }

    val q = new SearchQuery(
      text = queryText,
      rpp = rpp,
      folderIds = folderIds,
      personIds = personIds,
      page = page,
      searchSort = sort
    )

    val results = app.service.library.search(q)

    if (page > results.totalPages) {
      halt(204)
    }

    /**
     * If this is not a continuous scroll request and a person view, render the larger results template with auxiliary person
     * view.
     */
    if (isContinuousScroll) {
      ssp(
        "/htmx/results_grid",
        Api.Field.Search.RESULTS -> results,
        Api.Field.Search.PAGE -> page,
        Api.Field.Search.IS_CONTINUOUS_SCROLL -> true
      )
    } else {
      var personOpt: Option[Person] = None

      if (personIds.size == 1) {
        personOpt = Some(app.service.person.getById(personIds.head))
        val personViewUrl = app.service.urlService.getUrlForPersonView(request, personOpt.get.persistedId)
        println(s"Person view URL: $personViewUrl")
      }

      ssp(
        "/includes/search_results",
        Api.Field.Search.RESULTS -> results,
        Api.Field.Search.PAGE -> page,
        Api.Field.Search.PERSON -> personOpt.orNull,
        Api.Field.Search.IS_CONTINUOUS_SCROLL -> false
      )
    }

  }

}
