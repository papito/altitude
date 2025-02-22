package software.altitude.core.controllers

import org.scalatra._
import play.api.libs.json.JsNull
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.DataScrubber
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.util.Util
import software.altitude.core.{ Const => C }

import java.lang.System.currentTimeMillis

class BaseApiController extends BaseController {
  val OK: ActionResult = Ok("{}")

  def unscrubbedReqJson: Option[JsObject] = Some(
    if (request.body.isEmpty) Json.obj() else Json.parse(request.body).as[JsObject]
  )

  private def requestMethod: String = request.getMethod.toLowerCase

  before() {
    contentType = "application/json; charset=UTF-8"

    // verify that requests with request body are not empty
    checkPayload()
  }

  override def logRequestStart(): Unit = logger.info(
    s"API ${request.getRequestURI} ${requestMethod.toUpperCase}, Body {${request.body}} Args: ${request.getParameterMap}"
  )

  override def logRequestEnd(): Unit = {
    val startTime: Long = request.getAttribute("startTime").asInstanceOf[Long]
    logger.info(s"API request END (${response.status}): ${request.getRequestURI} in ${currentTimeMillis - startTime}ms")

    if (RequestContext.readQueryCount.value > 0) {
      logger.info(s"API request READ queries: ${RequestContext.readQueryCount.value}")
    }
    if (RequestContext.writeQueryCount.value > 0) {
      logger.info(s"API request WRITE queries: ${RequestContext.writeQueryCount.value}")
    }
  }

  // override to disable this check in controllers that do not require a JSON payload for post and put
  private def checkPayload(): Unit = {
    if (List("post", "put").contains(requestMethod) && request.body.isEmpty) {
      throw ValidationException(C.Msg.Err.EMPTY_REQUEST_BODY)
    }
  }

  def scrubAndValidatedJson(scrubber: DataScrubber = DataScrubber(), validator: ApiRequestValidator): JsObject = {
    val scrubbedJson = scrubber.scrub(unscrubbedReqJson.get)
    validator.validate(scrubbedJson)
    scrubbedJson
  }

  error {
    case ex: ValidationException =>
      val jsonErrors = ex.errors.keys.foldLeft(Json.obj()) {
        (res, field) =>
          {
            val key = field
            res ++ Json.obj(key -> ex.errors(key))
          }
      }

      BadRequest(
        Json.obj(
          Api.Field.VALIDATION_ERROR -> ex.message,
          Api.Field.VALIDATION_ERRORS -> (if (ex.errors.isEmpty) JsNull else jsonErrors)
        ))
    case _: NotFoundException =>
      NotFound(Json.obj())
    case ex: Exception =>
      val strStacktrace = Util.logStacktrace(ex)

      InternalServerError(
        Json.obj(
          Api.Field.ERROR -> (if (ex.getMessage != null) ex.getMessage else ex.getClass.getName),
          Api.Field.STACKTRACE -> strStacktrace))
  }
}
