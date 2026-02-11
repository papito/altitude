package altitude.core.routes.web

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.routes.decorators.{extractToken, requireLogin}
import cask.Request
import org.slf4j.Logger

class WebController(using logger: Logger) extends cask.Routes:
  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  @cask.get("/")
  def index()(request: Request): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return cask.Redirect("/setup")
    }

    extractToken(request) match {
      case Some(token) =>
        App.altitude.service.user.getUserFromToken(token) match {
          case Some(user) =>
            logger.info(s"User authenticated: ${user.email}")
            cask.Redirect(s"/r/${user.lastActiveRepoId}")
          case None =>
            cask.Redirect(s"/login")
        }
      case None =>
        cask.Redirect(s"/login")
    }
  }

  @requireLogin()
  @cask.get("/r/:repoId")
  def repositoryView(repoId: String)(using request: Request): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return cask.Redirect("/setup")
    }

    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  @cask.get("/setup")
  def setup(): cask.Response[String] = {
    val payload = "<!doctype html>" + html.setup()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  initialize()
