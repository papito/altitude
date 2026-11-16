package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.BusyException
import altitude.core.EditConflictException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.models.EditRecipe
import altitude.core.models.EditState
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

object EditController:
  private val fields = Api.Field.Edit

  private def orNull(value: Option[Int]): ujson.Value = value.fold(ujson.Null: ujson.Value)(ujson.Num(_))

  /** Where the render stands: `renderedVersion` is the edit version whose edited images are stored, `rev` their revision */
  def statusJson(state: EditState): ujson.Obj =
    ujson.Obj(
      fields.EDIT_VERSION -> state.editVersion,
      fields.RENDERED_VERSION -> orNull(state.renderedVersion),
      fields.REV -> state.rev)

  /**
   * The upright size is that of the original the geometry is in, which the client cannot tell from the stored size: a browser
   * turns the original by its EXIF orientation
   */
  def stateJson(state: EditState): ujson.Obj =
    val json = statusJson(state)
    json(fields.EDITED) = state.edited
    json(fields.WIDTH) = orNull(state.width)
    json(fields.HEIGHT) = orNull(state.height)
    json(fields.UPRIGHT_WIDTH) = orNull(state.upright.map(_._1))
    json(fields.UPRIGHT_HEIGHT) = orNull(state.upright.map(_._2))
    json(fields.RECIPE) = state.recipe.toJson
    json

class EditController(using logger: Logger) extends BaseController:
  private val prefix = "api/edit"
  private val fields = Api.Field.Edit

  /** The asset's Edit; version 0 and the identity for an asset never edited */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId")
  def state(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson(jsonResponse(EditController.stateJson(App.altitude.service.assetEdit.get(assetId))))

  /**
   * `base` is the version the client saw; when the Edit has moved on since, a 409 carries the current state, which the client
   * adopts
   */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.put(f"/$prefix/r/:repoId/asset/:assetId")
  def save(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson {
      val body = unscrubbedJson.get
      val base = wholeNumber(body, fields.BASE)
      val recipe = EditRecipe.parse(body.obj.getOrElse(fields.RECIPE, throw ValidationException(C.Msg.Err.EDIT_RECIPE_REQUIRED)))
      try
        jsonResponse(
          EditController.stateJson(App.altitude.service.assetEdit.save(assetId, base, recipe, RequestContext.getAccount)))
      catch
        case ex: EditConflictException =>
          jsonResponse(
            ujson.Obj(Api.Field.ERROR -> C.Msg.Err.EDIT_CONFLICT, fields.STATE -> EditController.stateJson(ex.state)),
            409)
    }

  /** The client polls this until the Edit is rendered */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/status")
  def status(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson(jsonResponse(EditController.statusJson(App.altitude.service.assetEdit.status(assetId))))

  /**
   * The Straighten that levels the original's lines as `recipe` turns and mirrors it, null when no lines agree on one. Nothing is
   * stored.
   */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/auto-straighten")
  def autoStraighten(repoId: String, assetId: String, recipe: Option[String] = None)(using request: Request): Response[String] =
    answeringJson {
      val edit = EditRecipe.parse(recipe.getOrElse(throw ValidationException(C.Msg.Err.EDIT_RECIPE_REQUIRED)))
      try
        jsonResponse(
          ujson.Obj(
            fields.ANGLE -> App.altitude.service.assetEdit
              .autoStraighten(assetId, edit)
              .fold(ujson.Null: ujson.Value)(ujson.Num(_))))
      catch case ex: BusyException => jsonError(ex.getMessage, 503)
    }

  private def wholeNumber(body: ujson.Obj, field: String): Int =
    body.obj
      .get(field)
      .flatMap(_.numOpt)
      .filter(n => n.isWhole && n >= 0 && n <= Int.MaxValue)
      .map(_.toInt)
      .getOrElse(throw ValidationException(C.Msg.Err.EDIT_WHOLE_NUMBER.format(field)))

  initialize()
