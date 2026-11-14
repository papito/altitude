package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.DuplicateException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.api.EditController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.routes.decorators.visibleOnly
import altitude.core.service.AssetEditService
import altitude.core.service.PersonService.FaceTarget
import altitude.core.util.JsonCodec.++

class AssetActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/asset"

  // A name given to a Face's Person, or to the Person a Face is given to, is trimmed and held to the name editor's length rules
  private val nameScrubber = DataScrubber(trim = List(Api.Field.Person.NAME))
  private val nameMaxLength = Map(Api.Field.Person.NAME -> Api.Constraints.MAX_NAME_LENGTH)
  private val nameMinLength = Map(Api.Field.Person.NAME -> Api.Constraints.MIN_NAME_LENGTH)

  /**
   * The asset's detail modal, or its JSON when JSON is asked for; an unknown asset, or another repository's, is a 404 of either,
   * as is a triaged asset or one in the trash for a Guest, who sees Browse alone (`visibleOnly`). Both carry the asset's Edit at
   * its head (`edit`, `EditController.headJson`), when it has one, and whether the account may edit the asset (`editable`): it
   * holds Edit images, and an Edit may change the asset (`AssetEditService.whyNotEditable`). For a holder of Edit images, an
   * asset no Edit may change also carries why (`editRefusal`), which the Edit toggle's title shows; the fragment carries the same
   * as data attributes, with the measurement's clipping levels and box, which the Edit panel's Clipping blink reads.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/asset-detail/:assetId")
  def showAssetDetailModal(repoId: String, assetId: String)(using request: Request): Response[String] =
    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))
    def asset: Asset = visibleOnly(App.altitude.service.asset.getById(assetId))
    def editOf(asset: Asset): Option[ujson.Obj] = App.altitude.service.assetEdit.headOf(asset).map(EditController.headJson)
    // Why the account's Edit toggle is disabled on the asset: nothing without Edit images, which has no toggle
    def refusal(asset: Asset): Option[String] =
      if RequestContext.holds(Permission.EditImages) then AssetEditService.whyNotEditable(asset) else None
    def editable(asset: Asset): Boolean =
      RequestContext.holds(Permission.EditImages) && AssetEditService.whyNotEditable(asset).isEmpty

    if isJsonFormat then
      answeringJson {
        val shown = asset
        val json = shown.toBrowserJson ++ ujson.Obj(Api.Field.Edit.EDITABLE -> editable(shown))
        editOf(shown).foreach(edit => json(Api.Field.Edit.EDIT) = edit)
        refusal(shown).foreach(why => json(Api.Field.Edit.REFUSAL) = why)
        jsonResponse(json)
      }
    else
      answering {
        val shown = asset
        htmlResponse(htmx.html.view_image_detail_modal(shown, editOf(shown).map(ujson.write(_)), editable(shown), refusal(shown)))
      }

  /**
   * The Detail panel's two sections, People and Details, for the asset, read in one snapshot (`AssetService.detailOf`): its Faces
   * with their people (bad matches left out, Hidden marked), the folder path (nothing for the root or for triage), its albums and
   * Locations, and the frames of its Sequence. An asset in the trash gets the same panel with its People section read-only; an
   * unknown asset, or another repository's, is a 404, as is a triaged asset or one in the trash for a Guest (`visibleOnly`).
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/detail-panel/:assetId")
  def showDetailPanel(repoId: String, assetId: String)(using request: Request): Response[String] =
    answering {
      val detail = App.altitude.service.asset.detailOf(assetId)
      visibleOnly(detail.asset): Unit
      val payload = "<!doctype html>" + htmx.html.detail_panel(detail)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /**
   * Change: gives the Face to the Person of the body's `personId`, or to the Person named by its `name` (found in any case, or
   * created), and answers the asset's People section with the result in its data attributes. A body that is not a JSON object or
   * not JSON at all, one naming both or neither, a name outside the length rules of the name editor, a reserved name or the
   * Face's own Person is a 400; a Face gone or moved meanwhile, or an unknown or no longer live Person, a 404; a Face of an asset
   * in the trash a 409.
   */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.put(f"/$prefix/r/:repoId/face/:faceId/person")
  def changeFacePerson(repoId: String, faceId: String)(using request: Request): Response[String] =
    answering {
      val json: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)
      val personId = json.obj.get(Api.Field.PERSON_ID).flatMap(_.strOpt).filter(_.nonEmpty)
      val name = json.obj.get(Api.Field.Person.NAME).flatMap(_.strOpt).filter(_.nonEmpty)

      val target = (personId, name) match
        case (Some(id), None) =>
          ApiRequestValidator(uuid = List(Api.Field.PERSON_ID)).validate(json)
          FaceTarget.ExistingPerson(id)
        case (None, Some(personName)) =>
          ApiRequestValidator(maxLengths = nameMaxLength, minLengths = nameMinLength).validate(json)
          FaceTarget.Named(personName)
        case _ => throw ValidationException(C.Msg.Err.FACE_TARGET_REQUIRED)

      val move = App.altitude.service.person.moveFace(faceId, target)
      logger.info(
        s"Moved face [$faceId] of asset [${move.face.assetId.get}] from person [${move.source.persistedId}] " +
          s"to [${move.target.persistedId}]${if move.sourceDeleted then ", deleting the source" else ""}")

      peopleSection(
        move.face.assetId.get,
        C.Msg.FaceCorrection.MOVED.format(move.target.name.getOrElse("")),
        Option.when(move.sourceDeleted)(move.source))
    }

  /**
   * Name: names the Face's Person as a whole (`PersonService.nameFacePerson`), the body's `personId` being the Person the panel
   * showed with the Face and its `name` the new name, and answers the asset's People section with the result in its data
   * attributes. A body that is not a JSON object, a `personId` that is not a UUID, a name outside the length rules of the name
   * editor, a reserved name or a name another live Person holds is a 400; a Face gone or given to someone else meanwhile, or a
   * Person no longer live, a 404; a Face of an asset in the trash, or a Person who has a name by now, a 409, as the section the
   * client shows is stale.
   */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.put(f"/$prefix/r/:repoId/face/:faceId/person/name")
  def nameFacePerson(repoId: String, faceId: String)(using request: Request): Response[String] =
    answering {
      val json: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)
      ApiRequestValidator(
        required = List(Api.Field.PERSON_ID, Api.Field.Person.NAME),
        uuid = List(Api.Field.PERSON_ID),
        maxLengths = nameMaxLength,
        minLengths = nameMinLength
      ).validate(json)
      val personId = json(Api.Field.PERSON_ID).str
      val name = json(Api.Field.Person.NAME).str

      try
        val naming = App.altitude.service.person.nameFacePerson(faceId, personId, name)
        logger.info(
          s"Named person [$personId] [${naming.after.name.get}], formerly [${naming.before.name.get}], " +
            s"from face [$faceId] of asset [${naming.face.assetId.get}]")

        peopleSection(naming.face.assetId.get, C.Msg.FaceCorrection.NAMED.format(naming.before.name.get, naming.after.name.get))
      catch
        case _: DuplicateException =>
          logger.debug(s"Name [$name] for person [$personId] of face [$faceId] is held by another person")
          textError(C.Msg.Err.PERSON_NAME_ALREADY_EXISTS, 400)
    }

  /**
   * Merge: merges the Face's Person as a whole into a named Person (`PersonService.mergeFacePerson`), the body's `personId` being
   * the Person the panel showed with the Face and its `destPersonId` the Person they are merged into, and answers the asset's
   * People section with the result in its data attributes, the merged Person's ID and the destination's among them. A body that
   * is not a JSON object, an ID that is not a UUID, a destination with no name or the Person themselves is a 400; a Face gone or
   * given to someone else meanwhile, or a Person or destination no longer live or of another Library, a 404; a Face of an asset
   * in the trash, or a Person who has a name by now, a 409, as the section the client shows is stale. It merges People, so it is
   * Organize's, not Edit metadata's as the other corrections are.
   */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/face/:faceId/person/merge")
  def mergeFacePerson(repoId: String, faceId: String)(using request: Request): Response[String] =
    answering {
      val json: ujson.Obj = unscrubbedJson.get
      val ids = List(Api.Field.PERSON_ID, Api.Field.Person.DEST_PERSON_ID)
      ApiRequestValidator(required = ids, uuid = ids).validate(json)
      val personId = json(Api.Field.PERSON_ID).str
      val destId = json(Api.Field.Person.DEST_PERSON_ID).str

      val merge = App.altitude.service.person.mergeFacePerson(faceId, personId, destId)
      logger.info(
        s"Merged person [$personId] [${merge.before.name.get}] into [$destId] [${merge.after.name.get}] " +
          s"from face [$faceId] of asset [${merge.face.assetId.get}]")

      peopleSection(
        merge.face.assetId.get,
        C.Msg.FaceCorrection.MERGED.format(merge.before.name.get, merge.after.name.get),
        merge = Some(merge.before -> merge.after))
    }

  /**
   * Remove: deletes the Face and its files, and answers the asset's People section with the result in its data attributes. A Face
   * gone or moved meanwhile is a 404, a Face of an asset in the trash a 409.
   */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.delete(f"/$prefix/r/:repoId/face/:faceId")
  def removeFace(repoId: String, faceId: String)(using request: Request): Response[String] =
    answering {
      val removal = App.altitude.service.person.removeFace(faceId)
      logger.info(
        s"Removed face [$faceId] of person [${removal.person.persistedId}] from asset [${removal.face.assetId.get}]" +
          s"${if removal.personDeleted then ", deleting the person" else ""}")

      peopleSection(
        removal.face.assetId.get,
        C.Msg.FaceCorrection.REMOVED.format(removal.person.name.getOrElse("")),
        Option.when(removal.personDeleted)(removal.person))
    }

  /**
   * The People section after a correction, its root carrying the message, any Person the correction deleted and, after a merge,
   * the merged Person and the Person they were merged into
   */
  private def peopleSection(
      assetId: String,
      message: String,
      deletedPerson: Option[Person] = None,
      merge: Option[(Person, Person)] = None): Response[String] =
    val asset: Asset = App.altitude.service.asset.getById(assetId)
    val faces = App.altitude.service.person.getAssetFacesForDetail(assetId)
    val payload = "<!doctype html>" + htmx.html.detail_panel_people(asset, faces, Some(message), deletedPerson, merge)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
