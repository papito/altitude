package altitude.core.unit

import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.shouldBe
import org.slf4j.{ Logger, LoggerFactory }

import altitude.core.Api
import altitude.core.routes.BaseController

@DoNotDiscover class BaseControllerTests extends funsuite.AnyFunSuite {

  private given Logger = LoggerFactory.getLogger(getClass)

  private object controller extends BaseController

  test("A dialog's success detail is ASCII in its header, so text outside Latin-1 reaches the client intact") {

    /**
     * Setup:
     *
     * A dialog success response whose detail is a JSON object with a name in German and Japanese ("Zürich 東京").
     *
     * Assertions:
     *
     * The success detail header is pure ASCII, and parsing it as JSON gives back the original name unchanged.
     */
    val header = controller
      .dialogSuccessResponse(ujson.Obj("name" -> "Zürich 東京"))
      .headers
      .collectFirst { case (Api.Field.SUCCESS_DETAIL_HEADER, value) => value }
      .get

    header.forall(_ < 128) shouldBe true
    ujson.read(header)("name").str shouldBe "Zürich 東京"
  }
}
