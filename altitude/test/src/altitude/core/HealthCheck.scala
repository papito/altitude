package altitude.core

import io.undertow.Undertow
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HealthCheck extends AnyFlatSpec with Matchers {

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

  "MinimalApplication" should "respond correctly to various HTTP requests" in {
    withServer(Boot) { host =>
      requests.get(s"$host/api/health", check = false).statusCode shouldBe 200
    }
  }
}
