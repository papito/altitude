package software.altitude.core.controllers.web

import software.altitude.core.{Const => C}
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.util.{SearchQuery, SearchResult}

class IndexController extends BaseWebController {

  get("/") {
    // Kick to setup if this is a new install
    if (!app.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      redirect("/setup")
    }

    requireLogin()

    contentType = "text/html"

    val q = new SearchQuery(
      rpp = C.Api.Search.DEFAULT_RPP,
    )

    val results: SearchResult = app.service.library.search(q)

    println(results.total)

    layoutTemplate(
      "/WEB-INF/templates/views/index.ssp",
        "results" -> results,
    )
  }

  get("/setup") {
    contentType = "text/html"

    if (app.isInitialized) {
      redirect("/")
    } else {
      layoutTemplate("/WEB-INF/templates/views/setup.ssp")
    }

  }
}
