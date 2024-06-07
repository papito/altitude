package software.altitude.core.controllers.web

import org.scalatra.ScalatraServlet
import org.scalatra.scalate.ScalateSupport

class WebIndexController extends ScalatraServlet with ScalateSupport {

  get("/") {
    contentType = "text/html"
    mustache("/index")
  }
}
