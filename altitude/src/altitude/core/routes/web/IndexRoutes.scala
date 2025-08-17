package altitude.core.routes.web

import org.slf4j.Logger

class IndexRoutes(using logger: Logger) extends cask.Routes:
  private val prefix = ""

  @cask.get(s"/$prefix")
  def index(): String = {
    logger.info("Serving index page")
    "This is Altitude DAM"

  }

  initialize()
