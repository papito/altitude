package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class ViewSettingsActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/view-settings"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/view-settings")
  def showViewSettingsDialog(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.view_settings_dialog()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
