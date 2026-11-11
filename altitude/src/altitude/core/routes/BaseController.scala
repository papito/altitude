package altitude.core.routes

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.RestoreResult

abstract class BaseController(using logger: Logger) extends cask.Routes:

  /**
   * Get the unscrubbed JSON from the request body. This validates that the content type is application/json if specified. Returns
   * an empty JSON object if the request body is empty. A body that is not JSON, or not a JSON object, is a `ValidationException`
   * as a wrong content type is: the client's mistake, not the route's failure.
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

  /** A JSON API response; `jsonError` is the `{"error": ...}` shape every JSON route uses for a client or upstream failure */
  def jsonResponse(value: ujson.Value, status: Int = 200): Response[String] =
    cask.Response(value.toString, status, Seq(("Content-Type", "application/json")))

  def jsonError(message: String, status: Int): Response[String] = jsonResponse(ujson.Obj("error" -> message), status)

  /** An HTMX partial's response: the rendered template as an HTML document, with any further headers */
  def htmlResponse(payload: Html, headers: (String, String)*): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, ("Content-Type", "text/html") +: headers)

  /** A 204 for an HTMX request: nothing to swap */
  def noContentResponse: Response[String] = cask.Response("", 204, Seq(("Content-Type", "text/html")))

  /** A plain-text refusal, which the snackbar reports: the shape of every refusal `answering` makes */
  def textError(message: String, status: Int): Response[String] =
    cask.Response(message, status, Seq(("Content-Type", "text/plain")))

  /** What a restore did: the shape the client reads to report a duplicate, wherever assets leave the trash */
  def restoreResponse(result: RestoreResult): Response[String] =
    jsonResponse(
      ujson.Obj(
        Api.Field.Asset.RESTORED -> result.restored.toSeq,
        Api.Field.Asset.DUPLICATES -> result.duplicates.toSeq
      ))

  /**
   * The selection a batch dialog submits: its hidden `Api.Field.ASSET_IDS` field is a comma-separated list, unlike the array the
   * JSON API takes. Every ID must be a UUID, and an empty selection is an error on that field (`ValidationException`).
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

  /**
   * The answer of a route whose refusals are plain text the snackbar reports: a bad request (`ValidationException`, its message
   * or its field errors) is a 400, something not found a 404 and a conflict (`IllegalOperationException`) a 409; any other
   * failure is the route's own.
   */
  def answering(f: => Response[String]): Response[String] =
    try f
    catch
      case ex: ValidationException => textError(whyInvalid(ex), 400)
      case ex: NotFoundException => textError(ex.getMessage, 404)
      case ex: IllegalOperationException => textError(ex.getMessage, 409)

  /**
   * [[answering]] for a JSON route, in the `{"error": ...}` shape (`jsonError`): a bad request (`ValidationException`, saying why
   * as [[answering]] says it) and a refused operation (`IllegalOperationException`) are a 400, and something not found, an
   * unknown ID or another repository's, a 404; any other failure is the route's own.
   */
  def answeringJson(f: => Response[String]): Response[String] =
    try f
    catch
      case ex: ValidationException => jsonError(whyInvalid(ex), 400)
      case ex: NotFoundException => jsonError(ex.getMessage, 404)
      case ex: IllegalOperationException => jsonError(ex.getMessage, 400)

  /** What a bad request says: its message, or its field errors when it has none */
  private def whyInvalid(ex: ValidationException): String =
    if ex.message.nonEmpty then ex.message else ex.errors.values.mkString(", ")

  /**
   * Response for a dialog form that failed validation: the rendered form (with errors and the submitted values) replaces the
   * submitting form itself, instead of going to the form's normal target. The client treats a retargeted response as a validation
   * replacement rather than a completed operation.
   *
   * The swap settles immediately: htmx's settle step briefly gives the new form's fields the old ones' attributes, and for a text
   * field that copy writes the old (empty) value attribute over the submitted value, which the field then keeps once it has
   * focus.
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
   * Response for a dialog operation that completed with an outcome only the server knows (how many assets a membership change
   * applied): an empty body, as for any completed operation, and `detail` as JSON in `Api.Field.SUCCESS_DETAIL_HEADER`, which the
   * client merges into the success detail the dialog declares (js/fragments/dialog-operations.js). A header value is Latin-1
   * bytes, so the JSON escapes every non-ASCII character.
   */
  def dialogSuccessResponse(detail: ujson.Obj): Response[String] =
    cask.Response(
      "",
      200,
      Seq(("Content-Type", "text/html"), (Api.Field.SUCCESS_DETAIL_HEADER, ujson.write(detail, escapeUnicode = true))))
