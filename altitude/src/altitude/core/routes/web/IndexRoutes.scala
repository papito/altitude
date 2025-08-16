package altitude.core.routes.web

class IndexRoutes extends cask.Routes:
  private val prefix = ""

  @cask.get(s"/$prefix")
  def index(): String = {
    "This is Altitude DAM"
  }

  initialize()
