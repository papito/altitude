package altitude.core.routes.web.partial

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class NavController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  /**
   * The nav with fresh counts. `current` is the entry of the page reloading it (a `Const.NavEntry` key), which the swap would
   * otherwise unmark.
   */
  @requireLogin()
  @cask.get(f"/$prefix/nav/r/:repoId")
  def htmxAdminSetup(repoId: String, current: String = ""): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + includes.html.nav(stats, current = current)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
