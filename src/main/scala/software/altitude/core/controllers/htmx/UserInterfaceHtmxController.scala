package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.controllers.BaseHtmxController

class UserInterfaceHtmxController extends BaseHtmxController {

  // This just clears the body of the modal
  val closeModal: Route = get("/close-modal") {
    ssp("htmx/close_modal")
  }
}
