package altitude.core.routes.web

import org.slf4j.Logger

class StaticController(using logger: Logger) extends cask.Routes:
  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  initialize()

