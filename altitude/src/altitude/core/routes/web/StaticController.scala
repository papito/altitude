package altitude.core.routes.web

import org.slf4j.Logger

class StaticController(using logger: Logger) extends cask.Routes:
  @cask.staticResources("/static/")
  def staticFileRoutes() = "static"

  initialize()
