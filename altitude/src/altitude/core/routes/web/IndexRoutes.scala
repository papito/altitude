package altitude.core.routes.web

import altitude.core.App
import org.slf4j.Logger

class IndexRoutes(using logger: Logger) extends cask.Routes:
  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  @cask.get("/")
  def index(): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return cask.Redirect("/setup")
    }

    val payload = "<!doctype html>" + html.index()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }

  @cask.get("/setup")
  def setup(): cask.Response[String] = {
    val payload = "<!doctype html>" + html.setup()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  initialize()
