package software.altitude.test.core.controller

import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.test.core.ControllerTestCore

@DoNotDiscover class IndexControllerTests(override val testApp: Altitude) extends ControllerTestCore {

  test("New installation goes to setup page") {
    get("/") {
      testApp.service.system.readMetadata.isInitialized should equal(false)
      status should equal(302)
      response.getHeader("Location") should endWith("/setup")
    }
  }
}
