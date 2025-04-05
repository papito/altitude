package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.controllers.BaseHtmxController

/** @ /htmx/nav/ */

class NavigationController extends BaseHtmxController  {

  before() {
    requireLogin()
  }

  val navigationBar: Route = get("/r/:repoId") {
    ssp(
      "includes/nav.ssp",
      "stats" -> app.service.stats.getStats)
  }

}
