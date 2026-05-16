package altitude.core.controller

import io.undertow.Undertow
import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.App

@DoNotDiscover class HealthControllerTests extends ControllerTestCore {

  test("Health endpoint should return HTTP 200") {
    withServer(App)(host => requests.get(s"$host/api/health").statusCode shouldBe 200)
  }
}
