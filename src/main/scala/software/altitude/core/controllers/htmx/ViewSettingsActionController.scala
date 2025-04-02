package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.{Api, Const => C}

/** @ /htmx/view-settings/ */
class ViewSettingsActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val showUpdateViewSettingsModal: Route = get("/r/:repoId/modals/view-settings") {
    ssp(
      "htmx/view_settings_modal",
      Api.Modal.MIN_WIDTH -> C.UI.VIEW_SETTINGS_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.VIEW_SETTINGS_MODAL_TITLE,
    )
  }
}
