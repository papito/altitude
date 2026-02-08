package altitude.core.routes.web

import altitude.core.App
import org.slf4j.Logger

class WebController(using logger: Logger) extends cask.Routes:
  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  @cask.get("/")
  def index(): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return cask.Redirect("/setup")
    }

    // FIXME: this is not a completed endpoint, we should redirect to the last active repo for the user after login
    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }

  @cask.get("/r/:repoId")
  def repositoryView(repoId: String): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return cask.Redirect("/setup")
    }

    App.altitude.service.repository.setContextFromRequest(Some(repoId))

    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats)

    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  @cask.get("/setup")
  def setup(): cask.Response[String] = {
    val payload = "<!doctype html>" + html.setup()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  initialize()
