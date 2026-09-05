package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.DuplicateException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Folder
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class FolderActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/folder"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/add-folder")
  def showAddFolderModal(repoId: String, parentId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.add_folder_modal(
      title = C.UI.ADD_FOLDER_MODAL_TITLE,
      parentId = parentId
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/rename-folder")
  def showRenameFolderModal(repoId: String, id: String, parentId: Option[String] = None)(using request: Request): Response[String] =
    val folder: Folder = App.altitude.service.folder.getById(id)
    val payload = "<!doctype html>" + htmx.html.rename_folder_modal(
      title = C.UI.RENAME_FOLDER_MODAL_TITLE,
      existingName = Some(folder.name),
      id = id
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/delete-folder")
  def showDeleteFolderModal(repoId: String, id: String, parentId: Option[String] = None)(using request: Request): Response[String] =
    val folder: Folder = App.altitude.service.folder.getById(id)
    val payload = "<!doctype html>" + htmx.html.delete_folder_modal(
      title = C.UI.DELETE_FOLDER_MODAL_TITLE,
      folder = folder
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/context-menu")
  def showFolderContextMenu(repoId: String, folderId: String, parentId: Option[String] = None)(using
      request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.folder_context_menu(folderId = folderId)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showFoldersTab(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.folders()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/add")
  def htmxAddFolder(repoId: String)(using request: Request): Response[String] =
    val dataScrubber = DataScrubber(
      trim = List(Api.Field.Folder.NAME)
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Folder.NAME, Api.Field.Folder.PARENT_ID),
      maxLengths = Map(
        Api.Field.Folder.NAME -> Api.Constraints.MAX_FOLDER_NAME_LENGTH
      ),
      minLengths = Map(
        Api.Field.Folder.NAME -> Api.Constraints.MIN_FOLDER_NAME_LENGTH
      )
    )

    val jsonIn: ujson.Obj = dataScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String], parentId: String): Response[String] =
      val payload = "<!doctype html>" + htmx.html.add_folder_modal(
        title = C.UI.ADD_FOLDER_MODAL_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        parentId = parentId
      )
      modalFormValidationResponse(payload)

    try apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, parentId = jsonIn(Api.Field.Folder.PARENT_ID).str)

    val folderName = jsonIn(Api.Field.Folder.NAME).str
    val parentId = jsonIn(Api.Field.Folder.PARENT_ID).str

    try App.altitude.service.folder.add(folderName, parentId = Some(parentId))
    catch
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        return responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), parentId = parentId)

    // The client reloads the folder tree off the "folderAdded" event - no markup needed here
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/rename")
  def htmxRenameFolder(repoId: String)(using request: Request): Response[String] =
    val dataScrubber = DataScrubber(
      trim = List(Api.Field.Folder.NAME)
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Folder.NAME, Api.Field.ID),
      maxLengths = Map(
        Api.Field.Folder.NAME -> Api.Constraints.MAX_FOLDER_NAME_LENGTH
      ),
      minLengths = Map(
        Api.Field.Folder.NAME -> Api.Constraints.MIN_FOLDER_NAME_LENGTH
      ),
      uuid = List(Api.Field.ID)
    )

    val jsonIn: ujson.Obj = dataScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String], folderId: String): Response[String] =
      val payload = "<!doctype html>" + htmx.html.rename_folder_modal(
        title = C.UI.RENAME_FOLDER_MODAL_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        id = folderId
      )
      modalFormValidationResponse(payload)

    try apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, folderId = jsonIn(Api.Field.ID).str)

    val newName = jsonIn(Api.Field.Folder.NAME).str
    val folderId = jsonIn(Api.Field.ID).str

    try App.altitude.service.folder.rename(folderId = folderId, newName = newName)
    catch
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        return responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), folderId = folderId)

    cask.Response(newName, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/move")
  def htmxMoveFolder(repoId: String, movedFolderId: String, newParentId: String)(using request: Request): Response[String] =
    logger.info(s"Moving folder $movedFolderId to $newParentId")

    // short-circuit if this is a noop
    if movedFolderId == newParentId then return cask.Response("", 400, Seq(("Content-Type", "text/html")))

    // Call the movers
    try App.altitude.service.folder.move(movedFolderId, newParentId)
    catch
      case ex: DuplicateException =>
        return cask.Response(
          ex.message.getOrElse("Folder name already exists at this level"),
          409,
          Seq(("Content-Type", "text/html")))

    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/")
  def htmxDeleteFolder(repoId: String, id: String)(using request: Request): Response[String] =
    App.altitude.service.library.deleteFolderById(id)
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  initialize()
