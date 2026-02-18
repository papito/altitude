package altitude.core.routes.web

import altitude.core.App
import altitude.core.routes.decorators.extractToken
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger

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

    if devUser.nonEmpty then
      logger.info(s"User authenticated: ${devUser.get.email}")
      return Response("", 302, Seq("Location" -> s"/r/${devUser.get.lastActiveRepoId.get}"), Nil)

    extractToken(request) match
      case Some(token) =>
        App.altitude.service.user.getUserFromToken(token) match
          case Some(user) =>
            logger.info(s"User authenticated: ${user.email}")
            Response("", 302, Seq("Location" -> s"/r/${user.lastActiveRepoId.get}"), Nil)
          case None =>
            Response("", 302, Seq("Location" -> "/login"), Nil)
      case None =>
        Response("", 302, Seq("Location" -> "/login"), Nil)

  @requireLogin()
  @cask.get("/r/:repoId")
  def repositoryView(repoId: String, view: Option[String] = None): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    else
      val stats = App.altitude.service.stats.getStats
      val payload = "<!doctype html>" + html.index(stats = stats)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
