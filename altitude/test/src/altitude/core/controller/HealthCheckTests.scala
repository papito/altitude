package altitude.core.controller

import altitude.core.Boot
import io.undertow.Undertow
import org.scalatest.{DoNotDiscover, funsuite}
import org.scalatest.matchers.should.Matchers.shouldBe

@DoNotDiscover class HealthCheckTests extends funsuite.AnyFunSuite {

  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
            .addHttpListener(8081, "localhost")
            .setHandler(example.defaultHandler)
            .build

    server.start()

    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

  test("Health endpoint should return HTTP 200") {
    withServer(Boot) { host =>
      requests.get(s"$host/api/health", check = false).statusCode shouldBe 200
    }
  }
}
