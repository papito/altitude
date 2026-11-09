package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Asset
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.service.PersonService.FaceTarget

class AssetActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/asset"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/asset-detail/:assetId")
  def showAssetDetailModal(repoId: String, assetId: String)(using request: Request): Response[String] =
    val asset: Asset = App.altitude.service.asset.getById(assetId)

    val contentType = request.exchange.getRequestHeaders.getFirst("Content-Type")
    val accept = request.exchange.getRequestHeaders.getFirst("Accept")

    val isJsonFormat =
      (contentType != null && contentType.contains("application/json")) ||
        (accept != null && accept.contains("application/json"))
    if isJsonFormat then cask.Response(asset.toJson.toString, 200, Seq(("Content-Type", "application/json")))
    else
      val payload = "<!doctype html>" + htmx.html.view_image_detail_modal(asset)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * Change: gives the Face to the Person of the body's `personId`, or to the Person named by its `name` (found in any case, or
   * created), and answers the asset's People section with the result in its data attributes. A body naming both or neither, a
   * name outside the length rules of the name editor, a reserved name or the Face's own Person is a 400; a Face gone or moved
   * meanwhile, or an unknown or no longer live Person, a 404; a Face of an asset in the trash a 409.
   */
  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/face/:faceId/person")
  def changeFacePerson(repoId: String, faceId: String)(using request: Request): Response[String] =
    val json: ujson.Obj = DataScrubber(trim = List(Api.Field.Person.NAME)).scrub(unscrubbedJson.get)
    val personId = json.obj.get(Api.Field.PERSON_ID).flatMap(_.strOpt).filter(_.nonEmpty)
    val name = json.obj.get(Api.Field.Person.NAME).flatMap(_.strOpt).filter(_.nonEmpty)

    correcting {
      val target = (personId, name) match
        case (Some(id), None) =>
          ApiRequestValidator(uuid = List(Api.Field.PERSON_ID)).validate(json)
          FaceTarget.ExistingPerson(id)
        case (None, Some(personName)) =>
          ApiRequestValidator(
            maxLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MAX_NAME_LENGTH),
            minLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MIN_NAME_LENGTH)
          ).validate(json)
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
   * Remove: deletes the Face and its files, and answers the asset's People section with the result in its data attributes. A Face
   * gone or moved meanwhile is a 404, a Face of an asset in the trash a 409.
   */
  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/face/:faceId")
  def removeFace(repoId: String, faceId: String)(using request: Request): Response[String] =
    correcting {
      val removal = App.altitude.service.person.removeFace(faceId)
      logger.info(
        s"Removed face [$faceId] of person [${removal.person.persistedId}] from asset [${removal.face.assetId.get}]" +
          s"${if removal.personDeleted then ", deleting the person" else ""}")

      peopleSection(
        removal.face.assetId.get,
        C.Msg.FaceCorrection.REMOVED.format(removal.person.name.getOrElse("")),
        Option.when(removal.personDeleted)(removal.person))
    }

  /** The People section after a correction, its root carrying the message and any Person the correction deleted */
  private def peopleSection(assetId: String, message: String, deletedPerson: Option[Person]): Response[String] =
    val asset: Asset = App.altitude.service.asset.getById(assetId)
    val faces = App.altitude.service.person.getAssetFacesForDetail(assetId)
    val payload = "<!doctype html>" + htmx.html.detail_panel_people(asset, faces, Some(message), deletedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /** The status a correction's refusal answers with, as plain text the snackbar reports */
  private def correcting(f: => Response[String]): Response[String] =
    def refused(message: String, status: Int): Response[String] =
      cask.Response(message, status, Seq(("Content-Type", "text/plain")))

    try f
    catch
      case ex: ValidationException =>
        refused(if ex.message.nonEmpty then ex.message else ex.errors.values.mkString(", "), 400)
      case ex: NotFoundException => refused(ex.getMessage, 404)
      case ex: IllegalOperationException => refused(ex.getMessage, 409)

  initialize()
