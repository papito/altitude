package altitude.core.routes

import cask.Request
import cask.model.Response
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

  /**
   * Response for a dialog form that failed validation: the rendered form (with errors and the submitted values) replaces the
   * submitting form itself, instead of going to the form's normal target. The client treats a retargeted response as a
   * validation replacement rather than a completed operation.
   *
   * The swap settles immediately: htmx's settle step briefly gives the new form's fields the old ones' attributes, and for a
   * text field that copy writes the old (empty) value attribute over the submitted value, which the field then keeps once it
   * has focus.
   */
  def dialogFormValidationResponse(payload: String): Response[String] =
    cask.Response(
      payload,
      200,
      Seq(
        ("Content-Type", "text/html"),
        ("HX-Retarget", "this"),
        ("HX-Reswap", "outerHTML settle:0")
      ))
