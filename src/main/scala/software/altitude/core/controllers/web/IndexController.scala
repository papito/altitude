package software.altitude.core.controllers.web

import software.altitude.core.controllers.BaseWebController

class IndexController extends BaseWebController {

  get("/") {
    // Kick to setup if this is a new install
    if (!app.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      redirect("/setup")
    }

    requireLogin()

    contentType = "text/html"
    layoutTemplate("/WEB-INF/templates/views/index.ssp")
  }

  get("/setup") {
    contentType = "text/html"

    if (app.isInitialized) {
      redirect("/")
    } else {
      layoutTemplate("/WEB-INF/templates/views/setup.ssp")
    }

  }
}
