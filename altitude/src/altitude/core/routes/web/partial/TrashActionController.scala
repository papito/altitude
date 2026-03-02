package altitude.core.routes.web.partial

import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger

class TrashActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/trash"

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/purge")
  def purgeRecycleBin(repoId: String)(using request: Request): Response[String] =
    logger.info("Purging the recycle bin")

    // Call the Sanitation Dept
    App.altitude.service.library.purgeRecycleBin()

    /**
     * The purge action is async, there is no point in reloading the recycle bin page right away as the user will not see any
     * changes until the next reload, so the only thing we can do is supply a happy message
     */
    val payload = "<!doctype html>" + htmx.html.trashbin_purged()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
