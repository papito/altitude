package altitude.core.routes

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.ForbiddenException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.RestoreResult
import altitude.core.routes.decorators.forbidden

object BaseController:

  /** The client's address, which an account event records */
  def clientIpOf(request: Request): Option[String] =
    Option(request.exchange.getSourceAddress).map(_.getAddress.getHostAddress)

abstract class BaseController(using logger: Logger) extends cask.Routes:

  /**
   * The request body as a JSON object, `{}` when empty. A wrong content type, or a body that is not a JSON object, is a
   * `ValidationException`: the client's mistake, not the route's failure.
   */
  def unscrubbedJson(using request: Request): Option[ujson.Obj] =
    // The header's media type, its parameters (a charset) left off; `Request.httpContentType` is geny's and always empty
    val mediaType = request.headers.get("content-type").flatMap(_.headOption).map(_.split(";")(0).trim.toLowerCase)
    if mediaType.exists(_ != "application/json") then throw ValidationException(C.Msg.Err.INVALID_CONTENT_TYPE)

    val text = request.text()
    if text.isEmpty then return Some(ujson.Obj())

    val parsed =
      try ujson.read(text)
      catch case _: ujson.ParsingFailedException => throw ValidationException(C.Msg.Err.MALFORMED_JSON)
    parsed match
      case obj: ujson.Obj => Some(obj)
      case _ => throw ValidationException(C.Msg.Err.MALFORMED_JSON)

  def jsonResponse(value: ujson.Value, status: Int = 200): Response[String] =
    cask.Response(value.toString, status, Seq(("Content-Type", "application/json")))

  // The shape every JSON route uses for a client or upstream failure
  def jsonError(message: String, status: Int): Response[String] = jsonResponse(ujson.Obj("error" -> message), status)

  def htmlResponse(payload: Html, headers: (String, String)*): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, ("Content-Type", "text/html") +: headers)

  /** Nothing for HTMX to swap */
  def noContentResponse: Response[String] = cask.Response("", 204, Seq(("Content-Type", "text/html")))

  /** A plain-text refusal, which the snackbar reports */
  def textError(message: String, status: Int): Response[String] =
    cask.Response(message, status, Seq(("Content-Type", "text/plain")))

  /** The shape the client reads to report duplicates, wherever assets leave the trash */
  def restoreResponse(result: RestoreResult): Response[String] =
    jsonResponse(
      ujson.Obj(
        Api.Field.Asset.RESTORED -> result.restored.toSeq,
        Api.Field.Asset.DUPLICATES -> result.duplicates.toSeq
      ))

  /**
   * A batch dialog's hidden `ASSET_IDS` field is comma-separated, unlike the JSON API's array. Every ID must be a UUID, and an
   * empty selection is an error on that field.
   */
  def selectionIds(json: ujson.Obj): Set[String] =
    val ids = json.obj
      .get(Api.Field.ASSET_IDS)
      .flatMap(_.strOpt)
      .getOrElse("")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
    val validator = ApiRequestValidator(uuid = List(Api.Field.ASSET_IDS))
    ids.foreach(id => validator.validate(ujson.Obj(Api.Field.ASSET_IDS -> id)))
    if ids.isEmpty then
      val errors = ValidationException()
      errors.errors += Api.Field.ASSET_IDS -> C.Msg.Err.VALUE_REQUIRED
      errors.trigger()
    ids

  /** Turns a route's expected failures into plain-text refusals the snackbar reports; any other failure is the route's own */
  def answering(f: => Response[String]): Response[String] =
    try f
    catch
      case ex: ValidationException => textError(whyInvalid(ex), 400)
      case ex: ForbiddenException => forbidden(ex.getMessage, json = false)
      case ex: NotFoundException => textError(ex.getMessage, 404)
      case ex: IllegalOperationException => textError(ex.getMessage, 409)

  /** [[answering]] for a JSON route, except that a refused operation is a 400 rather than a 409 */
  def answeringJson(f: => Response[String]): Response[String] =
    try f
    catch
      case ex: ValidationException => jsonError(whyInvalid(ex), 400)
      case ex: ForbiddenException => forbidden(ex.getMessage, json = true)
      case ex: NotFoundException => jsonError(ex.getMessage, 404)
      case ex: IllegalOperationException => jsonError(ex.getMessage, 400)

  /** What a bad request says: its message, or its field errors when it has none */
  private def whyInvalid(ex: ValidationException): String =
    if ex.message.nonEmpty then ex.message else ex.errors.values.mkString(", ")

  /**
   * For a dialog form that failed validation: the rendered form replaces the submitting form itself rather than the normal
   * target, which the client takes as a validation replacement, not a completed operation.
   *
   * `settle:0` restates the global `defaultSettleDelay: 0`. A settle would copy the old text field's empty value attribute over
   * the submitted value, which the field then keeps once it has focus.
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

  /**
   * For a dialog operation whose outcome only the server knows (how many assets a membership change applied): `detail` goes as
   * JSON in a header, merged by the client into the dialog's success detail. A header value is Latin-1, so the JSON escapes every
   * non-ASCII character.
   */
  def dialogSuccessResponse(detail: ujson.Obj): Response[String] =
    cask.Response(
      "",
      200,
      Seq(("Content-Type", "text/html"), (Api.Field.SUCCESS_DETAIL_HEADER, ujson.write(detail, escapeUnicode = true))))
