package altitude.core

import altitude.core.Altitude
import io.undertow.Undertow
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExampleTests extends AnyFlatSpec with Matchers {

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
    withServer(Altitude) { host =>
      val success = requests.get(host)

      success.statusCode shouldBe 200
      success.text() should include("Altitude")

      requests.get(s"$host/doesnt-exist", check = false).statusCode shouldBe 404

      requests.post(s"$host/do-thing", data = "hello").text() shouldBe "olleh"

      requests.delete(s"$host/do-thing", check = false).statusCode shouldBe 405
    }
  }
}
