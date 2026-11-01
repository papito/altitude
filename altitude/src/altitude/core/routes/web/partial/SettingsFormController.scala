package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.App
import altitude.core.ValidationException
import altitude.core.models.SettingType
import altitude.core.models.Settings
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireAdmin
import altitude.core.routes.decorators.requireLogin

object SettingsFormController:

  /**
   * The Settings form over the saved values, or over `submitted` with its `errors` beside the fields under a validation
   * replacement, each Setting that has one with a note on what the server makes of it. Only the Settings of this database engine
   * are rendered.
   */
  def form(submitted: Map[String, String] = Map.empty, errors: Map[String, String] = Map.empty): Html =
    val settings = App.altitude.settings
    val current = settings.current
    val shown = settings.applicable
    htmx.html.settings_form(
      settings = shown,
      values = shown.map(s => s.name -> current.value(s.name)).toMap ++ submitted,
      defaults = shown.map(s => s.name -> current.default(s.name)).toMap,
      errors = errors,
      pending = settings.pendingRestart.map(p => p.setting.name -> p).toMap,
      notes = Map(Settings.IMPORT_PARALLELISM -> s"Running ${App.altitude.importParallelismResolution.summary}")
    )

/** The one form of the Settings screen: its save answers the form again, with its errors or with the saved values */
class SettingsFormController(using logger: Logger) extends BaseController:

  @requireLogin()
  @requireAdmin()
  @cask.post("/htmx/settings/r/:repoId")
  def save(repoId: String)(using request: Request): Response[String] =
    val json = unscrubbedJson.get
    val typeErrors = ValidationException()

    // A Setting is text. A flag or a number may arrive typed (JSON true, a JSON number); a checkbox left unchecked is not
    // submitted at all, so a rendered flag with no value is false
    val submitted: Map[String, String] = App.altitude.settings.applicable.flatMap {
      setting =>
        json.obj.get(setting.name) match
          case Some(ujson.Str(value)) => Some(setting.name -> value)
          case Some(ujson.Bool(value)) => Some(setting.name -> value.toString)
          case Some(number: ujson.Num) => Some(setting.name -> number.toString)
          case Some(_) =>
            typeErrors.errors += setting.name -> C.Msg.Err.INCORRECT_VALUE_TYPE
            None
          case None if setting.valueType == SettingType.Flag => Some(setting.name -> "false")
          case None => None
    }.toMap

    try
      typeErrors.trigger()
      App.altitude.settings.save(submitted)
      cask.Response("<!doctype html>" + SettingsFormController.form(), 200, Seq(("Content-Type", "text/html")))
    catch
      case ex: ValidationException =>
        dialogFormValidationResponse("<!doctype html>" + SettingsFormController.form(submitted, errors = ex.errors.toMap))

  initialize()
