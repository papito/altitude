package altitude.core.routes.web

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.routes.decorators.extractToken
import altitude.core.routes.decorators.requireLogin

class IndexController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @cask.get("/")
  def index()(request: Request): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      return Response("", 302, Seq("Location" -> "/setup"), Nil)

    // Locally, we want to allow bypassing authentication with a dev user for easier testing.
    // This is a slightly different version of the one in the requireLogin decorator as the decorator cannot be used for
    // this endpoint.
    val devUser = App.altitude.service.user.getDevUser

    if devUser.isDefined then
      logger.debug(s"User authenticated: ${devUser.get.email}")
      return Response("", 302, Seq("Location" -> s"/r/${devUser.get.lastActiveRepoId.get}"), Nil)

    extractToken(request) match
      case Some(token) =>
        App.altitude.service.user.getUserFromToken(token) match
          case Some(user) =>
            logger.debug(s"User authenticated: ${user.email}")

            // Get the last active repo id for the user, and if it doesn't exist, set it to the default repo id and use that.
            val repoId = user.lastActiveRepoId.getOrElse {
              val defaultRepoId = App.altitude.service.repository.getDefaultRepository.persistedId
              App.altitude.service.user.setLastActiveRepoId(user, defaultRepoId)
              defaultRepoId
            }

            Response("", 302, Seq("Location" -> s"/r/$repoId"), Nil)
          case None =>
            Response("", 302, Seq("Location" -> "/login"), Nil)
      case None =>
        Response("", 302, Seq("Location" -> "/login"), Nil)

  @requireLogin()
  @cask.get("/r/:repoId")
  def repositoryView(
      repoId: String,
      view: Option[String] = None,
      personId: Option[String] = None,
      folderId: Option[String] = None,
      params: cask.QueryParams /* allow unknown params */ ): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    else mainPage(view.getOrElse(Const.Search.View.DEFAULT))

  /**
   * The address of one asset: the main page, over whose default results the client opens Asset detail on the asset named by the
   * path (js/search-results/detail-url.js, which also reads the Detail panel's `panel` parameter). An unknown asset, or another
   * repository's, is a 404 here, before any page is rendered around it.
   */
  @requireLogin()
  @cask.get("/r/:repoId/asset/:assetId")
  def assetView(repoId: String, assetId: String, params: cask.QueryParams /* allow unknown params */ ): cask.Response[String] =
    if App.altitude.service.asset.isInRepository(assetId) then mainPage(Const.Search.View.DEFAULT)
    else cask.Response("Asset not found", 404, Seq(("Content-Type", "text/plain")))

  /** The nav marks the view asked for; the client reads the same parameter to seed its search */
  private def mainPage(view: String): cask.Response[String] =
    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats, current = view)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
