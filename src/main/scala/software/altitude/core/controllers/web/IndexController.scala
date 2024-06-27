package software.altitude.core.controllers.web

import org.slf4j.LoggerFactory
import software.altitude.core.controllers.BaseWebController

class IndexController extends BaseWebController {
  private final val log = LoggerFactory.getLogger(getClass)

  get("/") {
    // Kick to setup if this is a new install
    if (!app.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      redirect("/setup")
    }

    requireLogin()

    contentType = "text/html"
    ssp("/index")
  }

  get("/setup") {
    contentType = "text/html"

    if (app.isInitialized) {
      redirect("/")
    } else {
      ssp("/setup")
    }

  }
}
