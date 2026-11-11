package altitude.core.unit

import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.shouldBe
import org.slf4j.{ Logger, LoggerFactory }

import altitude.core.Api
import altitude.core.ValidationException
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

  test("A JSON route's bad request is a JSON 400 saying why, as a plain-text route's is") {

    /**
     * Setup:
     *
     * Two JSON answers that fail validation: one with a message, and one with field errors alone.
     *
     * Assertions:
     *
     * Each is a 400 in the `{"error": ...}` shape, `application/json`, carrying the message, or the field errors joined, as the
     * plain-text answer of `answering` carries them.
     */
    val withMessage = controller.answeringJson(throw ValidationException("Not a UUID"))
    withMessage.statusCode shouldBe 400
    withMessage.headers.toMap.get("Content-Type") shouldBe Some("application/json")
    ujson.read(withMessage.data)("error").str shouldBe "Not a UUID"

    val fieldErrors = ValidationException()
    fieldErrors.errors += Api.Field.ASSET_IDS -> "Required"
    val withFieldErrors = controller.answeringJson(throw fieldErrors)
    withFieldErrors.statusCode shouldBe 400
    ujson.read(withFieldErrors.data)("error").str shouldBe "Required"
    controller.answering(throw fieldErrors).data shouldBe "Required"
  }
}
