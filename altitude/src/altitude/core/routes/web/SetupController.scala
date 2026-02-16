package altitude.core.routes.web

import cask.model.Response
import org.slf4j.Logger

class SetupController(using logger: Logger) extends cask.Routes:
  @cask.get("/setup")
  def setup(): cask.Response[String] =
    val payload = "<!doctype html>" + html.setup()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()

