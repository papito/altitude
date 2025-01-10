package software.altitude.core.controllers.web

import org.scalatra.Route

import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.RequestContext
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.models.User
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

class IndexController extends BaseWebController {

  val indexViewRepo: Route = get("/r/:repoId") {
    requireLogin()
    contentType = "text/html"

    val q = new SearchQuery(
      rpp = Const.Search.DEFAULT_RPP
    )

    val results: SearchResult = app.service.library.search(q)

    layoutTemplate(
      "/WEB-INF/templates/views/index.ssp",
      "results" -> results,
      "isServerSideLoad" -> true
    )
  }

  get("/") {
    // Kick to setup if this is a new install
    if (!app.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      redirect("/setup")
    }

    // else, go to the default repo view
    requireLogin()

    // if we the user is not going to a specific repo, redirect to the last active repo
    val user: User = RequestContext.getAccount
    require(user.lastActiveRepoId.isDefined, "User has no last active repo")

    redirect(url(indexViewRepo, Api.Field.REPO_ID -> user.lastActiveRepoId.get))
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
