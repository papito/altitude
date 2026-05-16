package altitude.core.routes.web.partial

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController

class NavController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  @cask.get(f"/$prefix/nav/r/:repoId")
  def htmxAdminSetup(repoId: String): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + includes.html.nav(stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
