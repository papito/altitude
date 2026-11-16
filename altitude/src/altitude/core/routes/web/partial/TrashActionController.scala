package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

class TrashActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/trash"

  @requireLogin()
  @requirePermission(Permission.Purge)
  @cask.delete(f"/$prefix/r/:repoId/purge")
  def purgeRecycleBin(repoId: String)(using request: Request): Response[String] =
    logger.debug("Purging the recycle bin")

    App.altitude.service.library.purgeRecycleBin()

    // The purge queue deletes the files in the background, so the answer is a message rather than a reloaded trash
    val payload = "<!doctype html>" + htmx.html.trashbin_purged()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
