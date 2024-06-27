package software.altitude.test.core.web

import org.scalatest.DoNotDiscover
import software.altitude.test.core.WebTestCore

@DoNotDiscover class IndexControllerTests(val config: Map[String, Any]) extends WebTestCore {

  test("New installation goes to setup page") {
    get("/") {
      altitude.isInitialized should equal(false)
      status should equal(302)
      response.getHeader("Location") should endWith("/setup")
    }
  }
}
