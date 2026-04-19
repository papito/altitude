package altitude.core.routes

import altitude.core.Const as C
import altitude.core.ValidationException
import cask.Request
import org.slf4j.Logger
import play.api.libs.json.JsObject
import play.api.libs.json.Json

abstract class BaseController(using logger: Logger) extends cask.Routes:

  /**
   * Get the unscrubbed JSON from the request body. This validates that the content type is application/json if specified. Returns
   * an empty JSON object if the request body is empty.
   */
  def unscrubbedJson(using request: Request): Option[JsObject] =
    if request.httpContentType.isDefined && request.httpContentType.get != "application/json" then
      throw ValidationException(C.Msg.Err.INVALID_CONTENT_TYPE)

    Some(if request.text().isEmpty then Json.obj() else Json.parse(request.text()).as[JsObject])
