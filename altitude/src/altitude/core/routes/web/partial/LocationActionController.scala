package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.DataScrubber
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Location
import altitude.core.models.LocationKind
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** Location dialogs use the shared operation lifecycle: a field error replaces the submitting form with its values intact. */
class LocationActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/location"
  private val fields = Api.Field.Location
  private val scrubber =
    DataScrubber(trim = List(fields.NAME, fields.PARENT_ID, fields.LATITUDE, fields.LONGITUDE, fields.RADIUS_M))

  private def parents: List[Location] = App.altitude.service.location.getAll.filter(_.kind == LocationKind.Parent)
  private def locations: List[Location] = App.altitude.service.location.getAll.filter(_.kind == LocationKind.Location)

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def tab(repoId: String)(using request: Request): Response[String] = html(htmx.html.locations())

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-location")
  def addLocationDialog(repoId: String)(using request: Request): Response[String] = html(addForm())

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-parent")
  def addParentDialog(repoId: String)(using request: Request): Response[String] = html(htmx.html.add_parent_dialog())

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/rename-location")
  def renameDialog(repoId: String, id: String)(using request: Request): Response[String] =
    withLocation(id) {
      location => html(htmx.html.rename_location_dialog(location, formJson = ujson.Obj(fields.NAME -> location.name)))
    }

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/delete-location")
  def deleteDialog(repoId: String, id: String)(using request: Request): Response[String] =
    withLocation(id)(location => html(htmx.html.delete_location_dialog(location)))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/move-location")
  def moveDialog(repoId: String, id: String)(using request: Request): Response[String] =
    withLocation(id) {
      location =>
        html(
          htmx.html
            .move_location_dialog(location, parents, formJson = ujson.Obj(fields.PARENT_ID -> location.parentId.getOrElse(""))))
    }

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-to-location")
  def addToLocationDialog(repoId: String)(using request: Request): Response[String] =
    html(htmx.html.add_to_location_dialog(locations))

  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/add")
  def add(repoId: String)(using request: Request): Response[String] =
    val json = scrubber.scrub(unscrubbedJson.get)
    submit(errors => addForm(errors, json), fields.PARENT_ID) {
      validate(json, required = List(fields.NAME), uuid = List(fields.PARENT_ID), coordinates = true)
      App.altitude.service.location.addLocation(
        json(fields.NAME).str,
        json(fields.LATITUDE).str.toDouble,
        json(fields.LONGITUDE).str.toDouble,
        optional(json, fields.PARENT_ID),
        optional(json, fields.RADIUS_M).map(_.toInt)
      )
    }

  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/add-parent")
  def addParent(repoId: String)(using request: Request): Response[String] =
    val json = scrubber.scrub(unscrubbedJson.get)
    submit(errors => htmx.html.add_parent_dialog(errors, json), fields.NAME) {
      validate(json, required = List(fields.NAME))
      App.altitude.service.location.addParent(json(fields.NAME).str)
    }

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/rename")
  def rename(repoId: String)(using request: Request): Response[String] =
    val json = scrubber.scrub(unscrubbedJson.get)
    withLocation(optional(json, Api.Field.ID).getOrElse("")) {
      location =>
        submit(errors => htmx.html.rename_location_dialog(location, errors, json), Api.Field.ID) {
          validate(json, required = List(fields.NAME))
          App.altitude.service.location.rename(location.persistedId, json(fields.NAME).str)
        }
    }

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/move")
  def move(repoId: String)(using request: Request): Response[String] =
    val json = scrubber.scrub(unscrubbedJson.get)
    withLocation(optional(json, Api.Field.ID).getOrElse("")) {
      location =>
        submit(errors => htmx.html.move_location_dialog(location, parents, errors, json), fields.PARENT_ID) {
          validate(json, required = Nil, uuid = List(fields.PARENT_ID))
          App.altitude.service.location.moveToParent(location.persistedId, optional(json, fields.PARENT_ID))
        }
    }

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/assets")
  def addAssets(repoId: String)(using request: Request): Response[String] =
    val json = scrubber.scrub(unscrubbedJson.get)
    submit(errors => htmx.html.add_to_location_dialog(locations, errors, json), fields.LOCATION_ID) {
      validate(json, required = List(fields.LOCATION_ID, Api.Field.ASSET_IDS), uuid = List(fields.LOCATION_ID))
      // HTMX's hidden field is a comma-separated selection, unlike the array accepted by the JSON API.
      val ids = json(Api.Field.ASSET_IDS).str.split(",").map(_.trim).filter(_.nonEmpty).toSet
      val validator = ApiRequestValidator(uuid = List(Api.Field.ASSET_IDS))
      ids.foreach(id => validator.validate(ujson.Obj(Api.Field.ASSET_IDS -> id)))
      if ids.isEmpty then
        val errors = ValidationException()
        errors.errors += Api.Field.ASSET_IDS -> Const.Msg.Err.VALUE_REQUIRED
        errors.trigger()
      App.altitude.service.location.addAssets(json(fields.LOCATION_ID).str, ids)
    }

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/")
  def delete(repoId: String, id: String)(using request: Request): Response[String] =
    withLocation(id) {
      location =>
        App.altitude.service.location.deleteById(location.persistedId)
        empty
    }

  /** An invalid hidden ID cannot be repaired in the dialog; reject it before lookup, without exposing a foreign row. */
  private def withLocation(id: String)(action: Location => Response[String]): Response[String] =
    try
      ApiRequestValidator(required = List(Api.Field.ID), uuid = List(Api.Field.ID)).validate(ujson.Obj(Api.Field.ID -> id))
      action(App.altitude.service.location.getById(id))
    catch
      case _: ValidationException => cask.Response("Invalid Location id", 400, Seq("Content-Type" -> "text/plain"))
      case ex: NotFoundException => cask.Response(ex.getMessage, 404, Seq("Content-Type" -> "text/plain"))

  private def addForm(errors: Map[String, String] = Map.empty, json: ujson.Obj = ujson.Obj()): Html =
    htmx.html.add_location_dialog(
      parents,
      App.altitude.config.getString(Const.Conf.MAP_TILE_URL),
      App.altitude.config.getString(Const.Conf.MAP_TILE_ATTRIBUTION),
      App.altitude.service.geocoder.isEnabled,
      errors,
      json
    )

  /** Both model violations and duplicate names are field errors; only a successful mutation completes the dialog. */
  private def submit(render: Map[String, String] => Html, operationField: String)(action: => Any): Response[String] =
    try
      action
      empty
    catch
      case ex: ValidationException => invalid(render, ex.errors.toMap)
      case ex: DuplicateException => invalid(render, Map(fields.NAME -> ex.message.getOrElse("Location name already exists")))
      case ex: IllegalOperationException => invalid(render, Map(operationField -> ex.getMessage))
      case ex: NotFoundException => invalid(render, Map(operationField -> ex.getMessage))

  private def invalid(render: Map[String, String] => Html, errors: Map[String, String]): Response[String] =
    logger.debug(s"Location dialog validation failed for ${errors.keys.mkString(", ")}")
    dialogFormValidationResponse("<!doctype html>" + render(errors))

  /** Forms submit strings; API callers may use JSON numbers for the pin and radius. Normalize only those numeric fields. */
  private def validate(json: ujson.Obj, required: List[String], uuid: List[String] = Nil, coordinates: Boolean = false): Unit =
    val errors = ValidationException()
    val numeric = List(fields.LATITUDE, fields.LONGITUDE, fields.RADIUS_M)
    for ((field, value) <- json.obj.toList) do
      value match
        case number: ujson.Num if numeric.contains(field) => json(field) = number.toString
        case ujson.Null if field == fields.PARENT_ID || field == fields.RADIUS_M => json.obj.remove(field)
        case _: ujson.Str =>
        case _ => errors.errors += field -> Const.Msg.Err.INCORRECT_VALUE_TYPE
    errors.trigger()

    // Empty optional inputs mean no parent or radius, and must not be passed to UUID or integer validation.
    List(fields.PARENT_ID, fields.RADIUS_M).foreach(field => if optional(json, field).isEmpty then json.obj.remove(field))
    try
      ApiRequestValidator(
        required = required,
        uuid = uuid,
        maxLengths = Map(fields.NAME -> Api.Constraints.MAX_LOCATION_NAME_LENGTH),
        minLengths = Map(fields.NAME -> Api.Constraints.MIN_LOCATION_NAME_LENGTH)
      ).validate(json)
    catch case ex: ValidationException => errors.errors ++= ex.errors

    if coordinates then
      List((fields.LATITUDE, -90, 90), (fields.LONGITUDE, -180, 180)).foreach {
        (field, min, max) =>
          if !optional(json, field).flatMap(_.toDoubleOption).exists(n => n >= min && n <= max) then
            errors.errors += field -> s"Enter a decimal between $min and $max"
      }
      if optional(json, fields.RADIUS_M).exists(value => !value.toIntOption.exists(_ > 0)) then
        errors.errors += fields.RADIUS_M -> "Enter a positive whole number of metres"
    errors.trigger()

  private def optional(json: ujson.Obj, field: String): Option[String] = json.obj.get(field).flatMap(_.strOpt).filter(_.nonEmpty)
  private def html(payload: Html): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, Seq("Content-Type" -> "text/html"))
  private def empty: Response[String] = cask.Response("", 200, Seq("Content-Type" -> "text/html"))

  initialize()
