package altitude.core.routes.web

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireAdmin
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.web.partial.SettingsFormController

/** The Settings screen, an Admin's page; its form is `SettingsFormController`'s */
class SettingsController(using logger: Logger) extends BaseController:

  @requireLogin()
  @requireAdmin()
  @cask.get("/settings/r/:repoId")
  def page(repoId: String): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.settings(stats = stats, form = SettingsFormController.form())
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
