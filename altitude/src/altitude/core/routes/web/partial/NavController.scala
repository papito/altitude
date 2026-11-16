package altitude.core.routes.web.partial

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class NavController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  /**
   * The nav with fresh counts. `current` (a `Const.NavEntry` key) keeps the page's entry marked across the swap; the Library menu
   * comes empty, as the page keeps its own (`hx-preserve`).
   */
  @requireLogin()
  @cask.get(f"/$prefix/nav/r/:repoId")
  def htmxAdminSetup(repoId: String, current: String = ""): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + includes.html.nav(stats, current = current, reload = true)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
