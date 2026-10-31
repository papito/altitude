package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.DuplicateException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Album
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/**
 * The Albums tab and its dialogs. The tab shell is a Twirl partial; the list itself is rendered client-side from
 * `AlbumController.getAlbumList`. Add is a modal dialog, rename and delete are inline dialogs in the album's menu, all following
 * the folder dialog flow (a validation failure replaces the form in place). Add to album is the batch ops' modal dialog: it adds
 * the selection to an album, or to a new one it creates.
 */
class AlbumActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/album"

  private val nameScrubber = DataScrubber(trim = List(Api.Field.Album.NAME))

  private def nameValidator(required: List[String], uuid: List[String] = List()) = ApiRequestValidator(
    required = required,
    maxLengths = Map(Api.Field.Album.NAME -> Api.Constraints.MAX_ALBUM_NAME_LENGTH),
    minLengths = Map(Api.Field.Album.NAME -> Api.Constraints.MIN_ALBUM_NAME_LENGTH),
    uuid = uuid
  )

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showAlbumsTab(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.albums()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-album")
  def showAddAlbumDialog(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.add_album_dialog()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/rename-album")
  def showRenameAlbumDialog(repoId: String, id: String)(using request: Request): Response[String] =
    val album: Album = App.altitude.service.album.getById(id)
    val payload = "<!doctype html>" + htmx.html.rename_album_dialog(
      title = C.UI.RENAME_ALBUM_DIALOG_TITLE,
      existingName = Some(album.name),
      id = id
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/delete-album")
  def showDeleteAlbumDialog(repoId: String, id: String)(using request: Request): Response[String] =
    val album: Album = App.altitude.service.album.getById(id)
    val payload = "<!doctype html>" + htmx.html.delete_album_dialog(title = C.UI.DELETE_ALBUM_DIALOG_TITLE, album = album)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/add")
  def htmxAddAlbum(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String]): Response[String] =
      val payload = "<!doctype html>" + htmx.html.add_album_dialog(fieldErrors = errors, formJson = jsonIn)
      dialogFormValidationResponse(payload)

    try nameValidator(required = List(Api.Field.Album.NAME)).validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap)

    try App.altitude.service.album.add(jsonIn(Api.Field.Album.NAME).str)
    catch
      case ex: DuplicateException =>
        return responseWithValidationErrors(Map(Api.Field.Album.NAME -> ex.message.getOrElse("Album name already exists")))

    // The client reloads the album list off the "albumAdded" event - no markup needed here
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/rename")
  def htmxRenameAlbum(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String], albumId: String): Response[String] =
      val payload = "<!doctype html>" + htmx.html.rename_album_dialog(
        title = C.UI.RENAME_ALBUM_DIALOG_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        id = albumId
      )
      dialogFormValidationResponse(payload)

    try nameValidator(required = List(Api.Field.Album.NAME, Api.Field.ID), uuid = List(Api.Field.ID)).validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, albumId = jsonIn(Api.Field.ID).str)

    val newName = jsonIn(Api.Field.Album.NAME).str
    val albumId = jsonIn(Api.Field.ID).str

    try App.altitude.service.album.rename(albumId = albumId, newName = newName)
    catch
      case ex: DuplicateException =>
        return responseWithValidationErrors(
          Map(Api.Field.Album.NAME -> ex.message.getOrElse("Album name already exists")),
          albumId = albumId)

    cask.Response(newName, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-to-album")
  def showAddToAlbumDialog(repoId: String)(using request: Request): Response[String] =
    val payload =
      "<!doctype html>" + htmx.html.add_to_album_dialog(C.UI.ADD_TO_ALBUM_DIALOG_TITLE, App.altitude.service.album.getAll)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The Add to album dialog's submission: the selection (`selectionIds`) goes to the chosen album, or, when no album is chosen,
   * to a new album by the submitted name, created in the same transaction. The success detail names the album and says how many
   * assets the server added (members already are skipped) and whether it created the album, which the client's list must then
   * show.
   */
  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/assets")
  def htmxAddAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)
    val albumId = jsonIn.obj.get(Api.Field.Album.ALBUM_ID).flatMap(_.strOpt).filter(_.nonEmpty)

    def responseWithValidationErrors(errors: Map[String, String]): Response[String] =
      logger.debug(s"Add to album validation failed for ${errors.keys.mkString(", ")}")
      val payload = "<!doctype html>" + htmx.html.add_to_album_dialog(
        C.UI.ADD_TO_ALBUM_DIALOG_TITLE,
        App.altitude.service.album.getAll,
        fieldErrors = errors,
        formJson = jsonIn)
      dialogFormValidationResponse(payload)

    def success(album: Album, added: Int, created: Boolean): Response[String] =
      dialogSuccessResponse(
        ujson.Obj(
          Api.Field.Album.ALBUM_ID -> album.persistedId,
          Api.Field.Album.NAME -> album.name,
          "added" -> added,
          "created" -> created))

    try
      val ids = selectionIds(jsonIn)
      albumId match
        case Some(id) =>
          ApiRequestValidator(uuid = List(Api.Field.Album.ALBUM_ID)).validate(jsonIn)
          // Fails with NotFoundException for an album of another repository or none at all
          val album: Album = App.altitude.service.album.getById(id)
          success(album, App.altitude.service.album.addAssets(id, ids), created = false)
        case None =>
          nameValidator(required = List(Api.Field.Album.NAME)).validate(jsonIn)
          val (album, added) = App.altitude.service.album.addAssetsToNewAlbum(jsonIn(Api.Field.Album.NAME).str, ids)
          success(album, added, created = true)
    catch
      case ex: ValidationException => responseWithValidationErrors(ex.errors.toMap)
      case ex: DuplicateException =>
        responseWithValidationErrors(Map(Api.Field.Album.NAME -> ex.message.getOrElse("Album name already exists")))
      case ex: NotFoundException => responseWithValidationErrors(Map(Api.Field.Album.ALBUM_ID -> ex.getMessage))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/")
  def htmxDeleteAlbum(repoId: String, id: String)(using request: Request): Response[String] =
    App.altitude.service.album.deleteById(id)
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  initialize()
