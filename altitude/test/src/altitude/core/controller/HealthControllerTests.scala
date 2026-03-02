package altitude.core.controller

import altitude.core.App
import io.undertow.Undertow
import org.scalatest.{DoNotDiscover, funsuite}
import org.scalatest.matchers.should.Matchers.shouldBe

@DoNotDiscover class HealthControllerTests extends ControllerTestCore {

  test("Health endpoint should return HTTP 200") {
    withServer(App) { host =>
      requests.get(s"$host/api/health").statusCode shouldBe 200
    }
  }
}
