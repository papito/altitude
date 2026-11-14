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

  /** Where the render of an Edit stands: its edit version, the edit version whose edited images are stored, and their revision */
  def statusJson(state: EditState): ujson.Obj =
    ujson.Obj(
      fields.EDIT_VERSION -> state.editVersion,
      fields.RENDERED_VERSION -> orNull(state.renderedVersion),
      fields.REV -> state.rev)

  /**
   * An Edit as a client sees it, which the asset detail's `edit` carries too: the render status, whether it is edited, its edited
   * size, the size of the upright original its geometry is in, which the client cannot tell from the stored size (a browser turns
   * the original by its EXIF orientation), and the Edit
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

/**
 * The Edit's JSON API (`AssetEditService`): an asset's Edit, a save, the render status and auto-straighten, all the holder of
 * Edit images'. An unknown asset, or another Library's, is a 404; a refused Edit or body, and an asset that cannot be edited (no
 * still image, or in the trash), a 400; a save built on a version the Edit has moved on from a 409 carrying the current state,
 * which the client adopts.
 */
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
   * Saves the body's `recipe` as the asset's Edit, built on the version `base` the client saw, and answers the new state, or a
   * 409 with the current one when the Edit has moved on since
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

  /** The version, the version whose edited images are stored and their revision, which the client polls until it is rendered */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/status")
  def status(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson(jsonResponse(EditController.statusJson(App.altitude.service.assetEdit.status(assetId))))

  /**
   * The Straighten that levels the lines of the asset's original as the Edit in `recipe` (its JSON) turns and mirrors it, which
   * the Straighten's Auto button sets: `{angle}`, null when no lines agree on one, so the client keeps its Straighten. Nothing is
   * stored. An original no image reader decodes is a 400, and a request while every auto-straighten slot is taken a 503 saying
   * so, which the client shows.
   */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/auto-straighten")
  def autoStraighten(repoId: String, assetId: String, recipe: Option[String] = None)(using request: Request): Response[String] =
    answeringJson {
      val edit = EditRecipe.parse(recipe.getOrElse(throw ValidationException(C.Msg.Err.EDIT_RECIPE_REQUIRED)))
      try
        jsonResponse(ujson.Obj(
          fields.ANGLE -> App.altitude.service.assetEdit.autoStraighten(assetId, edit).fold(ujson.Null: ujson.Value)(ujson.Num(_))))
      catch case ex: BusyException => jsonError(ex.getMessage, 503)
    }

  /** A field of the body that must be a whole number from 0: the version a save was built on */
  private def wholeNumber(body: ujson.Obj, field: String): Int =
    body.obj
      .get(field)
      .flatMap(_.numOpt)
      .filter(n => n.isWhole && n >= 0 && n <= Int.MaxValue)
      .map(_.toInt)
      .getOrElse(throw ValidationException(C.Msg.Err.EDIT_WHOLE_NUMBER.format(field)))

  initialize()
