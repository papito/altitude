package altitude.core.routes.web

import altitude.core.{App, RequestContext}
import altitude.core.routes.decorators.{extractToken, requireLogin}
import cask.Request
import cask.model.Response
import org.slf4j.Logger

class IndexController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @cask.get("/")
  def index()(request: Request): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      return Response("", 302, Seq("Location" -> "/setup"), Nil)

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
  def repositoryView(repoId: String)(using request: Request): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    else
      val stats = App.altitude.service.stats.getStats
      val payload = "<!doctype html>" + html.index(stats = stats)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()

