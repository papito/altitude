package altitude.core.routes

import cask.Request
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.ValidationException

abstract class BaseController(using logger: Logger) extends cask.Routes:

  /**
   * Get the unscrubbed JSON from the request body. This validates that the content type is application/json if specified. Returns
   * an empty JSON object if the request body is empty.
   */
  def unscrubbedJson(using request: Request): Option[ujson.Obj] =
    if request.httpContentType.isDefined && request.httpContentType.get != "application/json" then
      throw ValidationException(C.Msg.Err.INVALID_CONTENT_TYPE)

    Some(if request.text().isEmpty then ujson.Obj() else ujson.read(request.text()).asInstanceOf[ujson.Obj])
