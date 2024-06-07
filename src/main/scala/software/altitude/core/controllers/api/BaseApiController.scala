package software.altitude.core.controllers.api

import org.scalatra._
import org.slf4j.LoggerFactory
import play.api.libs.json.JsNull
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.NotFoundException
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseController
import software.altitude.core.{Const => C}

import scala.compat.Platform

class BaseApiController extends BaseController with ContentEncodingSupport {
  private final val log = LoggerFactory.getLogger(getClass)

  val OK: ActionResult = Ok("{}")

  val HTTP_POST_VALIDATOR: Option[ApiRequestValidator] = None
  val HTTP_DELETE_VALIDATOR: Option[ApiRequestValidator] = None
  val HTTP_UPDATE_VALIDATOR: Option[ApiRequestValidator] = None

  def requestJson: Option[JsObject] = Some(
    if (request.body.isEmpty) Json.obj() else Json.parse(request.body).as[JsObject]
  )

  def requestMethod: String = request.getMethod.toLowerCase

  before() {
    // verify that requests with request body are not empty
    checkPayload()

    /*
    Process all validators that may be set for this controller/method.
     */
    HTTP_POST_VALIDATOR match {
      case Some(ApiRequestValidator(_)) if requestMethod == "post" =>
        HTTP_POST_VALIDATOR.get.validate(requestJson.get)
      case _ if requestMethod == "post" =>
        log.debug(s"No POST validator specified for ${this.getClass.getName}")
      case _ =>
    }

    HTTP_DELETE_VALIDATOR match {
      case Some(ApiRequestValidator(_)) if requestMethod == "delete" =>
        HTTP_DELETE_VALIDATOR.get.validate(requestJson.get)
      case _ if requestMethod == "delete" =>
        log.debug(s"No DELETE validator specified for ${this.getClass.getName}")
      case _ =>
    }

    HTTP_UPDATE_VALIDATOR match {
      case Some(ApiRequestValidator(_)) if requestMethod == "put" =>
        HTTP_UPDATE_VALIDATOR.get.validate(requestJson.get)
      case _ if requestMethod == "update" =>
        log.debug(s"No PUT validator specified for ${this.getClass.getName}")
      case _ =>
    }

    // all responses are of type:
    contentType = "application/json; charset=UTF-8"
  }

  override def logRequestStart(): Unit = {
    log.info(
      s"API ${request.getRequestURI} ${requestMethod.toUpperCase} " +
        s"with {${request.body}} and ${request.getParameterMap}")
  }

  override def logRequestEnd(): Unit = {
    val startTime: Long = request.getAttribute("startTime").asInstanceOf[Long]
    log.info(s"API request END: ${request.getRequestURI} in ${Platform.currentTime - startTime}ms")
  }

  // override to disable this check in controllers that do not require a JSON payload for post and put
  private def checkPayload(): Unit = {
    if (List("post", "put").contains(requestMethod) && request.body.isEmpty) {
      throw ValidationException(C.Msg.Err.EMPTY_REQUEST_BODY)
    }
  }

  error {
    case ex: ValidationException => {
      val jsonErrors = ex.errors.keys.foldLeft(Json.obj()) {(res, field) => {
        val key = field.toString
        res ++ Json.obj(key -> ex.errors(key))}
      }

      BadRequest(Json.obj(
        C.Api.VALIDATION_ERROR -> ex.message,
        C.Api.VALIDATION_ERRORS -> (if (ex.errors.isEmpty) JsNull else jsonErrors)
      ))
    }
    case _: NotFoundException => {
      NotFound(Json.obj())
    }
    case ex: Exception => {
      val strStacktrace = software.altitude.core.Util.logStacktrace(ex)

      InternalServerError(Json.obj(
        C.Api.ERROR -> (if (ex.getMessage!= null) ex.getMessage else ex.getClass.getName),
        C.Api.STACKTRACE -> strStacktrace))
    }
  }
}
