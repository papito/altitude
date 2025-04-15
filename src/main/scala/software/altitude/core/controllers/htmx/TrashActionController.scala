package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Asset
import software.altitude.core.{Api, Const, DuplicateException}

/** @ /htmx/trash/ */
class TrashActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val purgeRecycleBin: Route = delete("/r/:repoId/purge") {
    logger.info(s"Purging the recycle bin")

    // Call the Sanitation Dept
    app.service.library.purgeRecycleBin()

    /**
     * The purge action is async, there is no point in reloading the recycle bin page right away as
     * the user will not see any changes until the next reload, so the only thing we can
     * do is supply a happy message
     */
    ssp("htmx/trashbin_purged")
  }
}
