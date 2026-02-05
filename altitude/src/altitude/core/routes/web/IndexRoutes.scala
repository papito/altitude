package altitude.core.routes.web

import org.slf4j.Logger

class IndexRoutes(using logger: Logger) extends cask.Routes:
  @cask.get(s"/")
  def index(): cask.Response[String] = {
    val payload = "<!doctype html>" + html.index()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }

  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  initialize()
