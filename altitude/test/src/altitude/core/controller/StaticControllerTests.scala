package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, startWith }

import altitude.core.{ App, Assets }

@DoNotDiscover class StaticControllerTests extends ControllerTestCore {

  test("The stylesheet bundle is served to anyone and cached for good") {

    /**
     * Setup:
     *
     * No user, repository or login.
     *
     * Assertions:
     *
     * The bundle the pages link answers 200 as CSS, carries the rules of `core.css`, and may be cached forever: its name
     * changes with its content. A cache keeps a copy per Accept-Encoding, since the body is gzipped only for a client that
     * accepts it.
     */
    withServer(App) {
      host =>
        val response = requests.get(s"$host${Assets.appCss}")

        response.statusCode shouldBe 200
        response.contentType.head should startWith("text/css")
        response.text() should include("--modal-content-width:")
        response.headers("cache-control") shouldBe Seq("public, max-age=31536000, immutable")
        response.headers("vary") shouldBe Seq("Accept-Encoding")
    }
  }

  test("A stylesheet bundle that is not there is not cached") {

    /**
     * Setup:
     *
     * No user, repository or login.
     *
     * Assertions:
     *
     * A name that is not the current bundle, such as a page open across a restart asking for the previous one, answers 404
     * with no Cache-Control: the name may be served once the bundle with that content is built again. So does the prefix
     * itself, which names no file.
     */
    withServer(App) {
      host =>
        Seq("/assets/app.00000000.css", "/assets/").foreach {
          path =>
            val response = requests.get(s"$host$path", check = false)

            withClue(path) {
              response.statusCode shouldBe 404
              response.headers.contains("cache-control") shouldBe false
            }
        }
    }
  }
}
