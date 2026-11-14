package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
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

  /** Where the render of an Edit stands: its head, the step whose renditions are stored, and their revision */
  def statusJson(state: EditState): ujson.Obj =
    ujson.Obj(fields.HEAD -> state.head, fields.RENDERED -> orNull(state.rendered), fields.REV -> state.rev)

  /** An Edit at its head with the head's label, as the asset detail's `edit` carries it */
  def headJson(state: EditState): ujson.Obj =
    val json = statusJson(state)
    json(fields.LABEL) = state.label
    json(fields.EDITED) = state.edited
    json(fields.WIDTH) = orNull(state.width)
    json(fields.HEIGHT) = orNull(state.height)
    json(fields.RECIPE) = state.recipe.toJson
    json

  /**
   * An Edit and its History as the account `viewerId` is answered it: the steps newest first and the Original last, each with its
   * author's name and time (none for the Original) and whether the viewer made it (`mine`), so the History names the author of
   * another's step alone without the account's ID leaving the server
   */
  def stateJson(state: EditState, viewerId: String): ujson.Obj =
    val json = headJson(state)
    json(fields.STEPS) = ujson.Arr.from(state.steps.map {
      step =>
        ujson.Obj(
          fields.SEQ -> step.seq,
          fields.LABEL -> step.label,
          fields.AUTHOR -> step.author.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
          fields.CREATED_AT -> step.createdAt.fold(ujson.Null: ujson.Value)(at => ujson.Str(at.toString)),
          fields.MINE -> step.accountId.contains(viewerId)
        )
    })
    json

/**
 * The Edit's JSON API (`AssetEditService`): an asset's Edit and its History, a step, a head move (Undo, Redo and a jump from the
 * History), the render status and auto-straighten, all the holder of Edit images'. An unknown asset, or another Library's, is a
 * 404; a refused Edit or body, and an asset that cannot be edited (no still image, or in the trash), a 400; a write built on a
 * head that has moved since a 409 carrying the current state, which the client adopts.
 */
class EditController(using logger: Logger) extends BaseController:
  private val prefix = "api/edit"
  private val fields = Api.Field.Edit

  /** The asset's Edit and its History; the Original for an asset never edited */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId")
  def state(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson(jsonResponse(EditController.stateJson(App.altitude.service.assetEdit.get(assetId), viewerId)))

  /** Saves the body's `recipe` as a step after the head `base` it was built on, and answers the new state */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.post(f"/$prefix/r/:repoId/asset/:assetId/steps")
  def addStep(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringEdit {
      val body = unscrubbedJson.get
      val base = wholeNumber(body, fields.BASE)
      val recipe = EditRecipe.parse(body.obj.getOrElse(fields.RECIPE, throw ValidationException(C.Msg.Err.EDIT_RECIPE_REQUIRED)))
      App.altitude.service.assetEdit.addStep(assetId, base, recipe, RequestContext.getAccount)
    }

  /** Moves the head from `base` to the step `seq` (0 for the Original), and answers the new state */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.put(f"/$prefix/r/:repoId/asset/:assetId/head")
  def moveHead(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringEdit {
      val body = unscrubbedJson.get
      val base = wholeNumber(body, fields.BASE)
      val seq = wholeNumber(body, fields.SEQ)
      App.altitude.service.assetEdit.moveHead(assetId, base, seq, RequestContext.getAccount)
    }

  /** The head, the step whose renditions are stored and their revision, which the client polls until the head is rendered */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/status")
  def status(repoId: String, assetId: String)(using request: Request): Response[String] =
    answeringJson(jsonResponse(EditController.statusJson(App.altitude.service.assetEdit.status(assetId))))

  /**
   * The Straighten that levels the lines of the asset's original as the Edit in `recipe` (its JSON) turns and mirrors it, which
   * the Straighten's Auto button sets: `{angle}`. Nothing is stored.
   */
  @requireLogin()
  @requirePermission(Permission.EditImages)
  @cask.get(f"/$prefix/r/:repoId/asset/:assetId/auto-straighten")
  def autoStraighten(repoId: String, assetId: String, recipe: Option[String] = None)(using request: Request): Response[String] =
    answeringJson {
      val edit = EditRecipe.parse(recipe.getOrElse(throw ValidationException(C.Msg.Err.EDIT_RECIPE_REQUIRED)))
      jsonResponse(ujson.Obj(fields.ANGLE -> App.altitude.service.assetEdit.autoStraighten(assetId, edit)))
    }

  /** A write's answer: the new state, or a 409 with the current one when the head has moved since the client saw it */
  private def answeringEdit(write: => EditState): Response[String] =
    answeringJson {
      try jsonResponse(EditController.stateJson(write, viewerId))
      catch
        case ex: EditConflictException =>
          jsonResponse(
            ujson.Obj(Api.Field.ERROR -> C.Msg.Err.EDIT_CONFLICT, fields.STATE -> EditController.stateJson(ex.state, viewerId)),
            409)
    }

  private def viewerId: String = RequestContext.getAccount.persistedId

  /** A field of the body that must be a whole number from 0: a head or a step */
  private def wholeNumber(body: ujson.Obj, field: String): Int =
    body.obj
      .get(field)
      .flatMap(_.numOpt)
      .filter(n => n.isWhole && n >= 0 && n <= Int.MaxValue)
      .map(_.toInt)
      .getOrElse(throw ValidationException(C.Msg.Err.EDIT_WHOLE_NUMBER.format(field)))

  initialize()
