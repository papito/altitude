package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.App
import altitude.core.ValidationException
import altitude.core.models.Settings
import altitude.core.models.SettingType
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireAdmin
import altitude.core.routes.decorators.requireLogin

object SettingsFormController:

  /** A JSON number as a Setting's text, in plain notation (`0.00002`, not `2.0E-5`), as a Setting is stored and shown as text */
  private def plainText(number: Double): String = BigDecimal(number).bigDecimal.stripTrailingZeros.toPlainString

  /** The Settings form over the saved values, or over `submitted` with its `errors`; only this database engine's Settings */
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

class SettingsFormController(using logger: Logger) extends BaseController:

  @requireLogin()
  @requireAdmin()
  @cask.post("/htmx/settings/r/:repoId")
  def save(repoId: String)(using request: Request): Response[String] =
    val json = unscrubbedJson.get
    val typeErrors = ValidationException()

    // A flag or a number may arrive typed; an unchecked checkbox is not submitted at all, so a flag with no value is false
    val submitted: Map[String, String] = App.altitude.settings.applicable.flatMap {
      setting =>
        json.obj.get(setting.name) match
          case Some(ujson.Str(value)) => Some(setting.name -> value)
          case Some(ujson.Bool(value)) => Some(setting.name -> value.toString)
          case Some(ujson.Num(number)) => Some(setting.name -> SettingsFormController.plainText(number))
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
