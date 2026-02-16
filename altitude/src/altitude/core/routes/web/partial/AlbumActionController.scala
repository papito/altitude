package altitude.core.routes.web.partial

import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger

class AlbumActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/album"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showAlbumsTab(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.albums()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()


