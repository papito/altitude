package software.altitude.core.controllers.web

import org.slf4j.LoggerFactory
import software.altitude.core.controllers.BaseWebController

class ImportController extends BaseWebController {
  private final val log = LoggerFactory.getLogger(getClass)

  get("/") {
    contentType = "text/html"
    ssp("/import")
  }
}
