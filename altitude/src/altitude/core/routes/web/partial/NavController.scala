package altitude.core.routes.web.partial

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController

class NavController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  /** The nav with fresh counts. `search` asks for the Search input, which only the main page's nav has. */
  @cask.get(f"/$prefix/nav/r/:repoId")
  def htmxAdminSetup(repoId: String, search: Boolean = false): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + includes.html.nav(stats, search = search)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
