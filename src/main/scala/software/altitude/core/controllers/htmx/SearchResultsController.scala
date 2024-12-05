package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchSort

class SearchResultsController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val htmxSearchResults: Route = get("/r/:repoId") {
    val rpp = params.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = params.getOrElse(Api.Field.Search.PAGE, "1").toInt
    val queryText = params.get(Api.Field.Search.QUERY_TEXT)
    val sortArg = params.get(Api.Field.Search.SORT)
    logger.info(s"Query string: $queryText")

    logger.info(s"Sort: $sortArg")

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
      page = page,
      searchSort = sort
    )

    val results = app.service.library.search(q)

    if (page > results.totalPages) {
      logger.info("!!! End of results reached")
      halt(204)
    }

    ssp(
      "/htmx/results_grid.ssp",
      Api.Field.Search.RESULTS -> results,
      Api.Field.Search.PAGE -> page
    )
  }

}
