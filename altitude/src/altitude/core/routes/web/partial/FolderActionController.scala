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
import altitude.core.models.Folder
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

class FolderActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/folder"

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/dialogs/add-folder")
  def showAddFolderDialog(repoId: String, parentId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.add_folder_dialog(parentId = parentId)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /** An unknown folder, or another repository's, is a 404 */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/dialogs/rename-folder")
  def showRenameFolderDialog(repoId: String, id: String, parentId: Option[String] = None)(using
      request: Request): Response[String] =
    answering {
      val folder: Folder = App.altitude.service.folder.getById(id)
      htmlResponse(
        htmx.html.rename_folder_dialog(
          title = C.UI.RENAME_FOLDER_DIALOG_TITLE,
          existingName = Some(folder.name),
          id = id
        ))
    }

  /** An unknown folder, or another repository's, is a 404 */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/dialogs/delete-folder")
  def showDeleteFolderDialog(repoId: String, id: String, parentId: Option[String] = None)(using
      request: Request): Response[String] =
    answering {
      val folder: Folder = App.altitude.service.folder.getById(id)
      htmlResponse(htmx.html.delete_folder_dialog(title = C.UI.DELETE_FOLDER_DIALOG_TITLE, folder = folder))
    }

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showFoldersTab(repoId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.folders()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * Adds a folder under `parentId`. A parent that is no folder of the repository, unknown or another repository's, answers the
   * dialog's form again with the error, as a name refused does, and adds nothing.
   */
  @requireLogin()
  @requirePermission(Permission.Organize)
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
      val payload = "<!doctype html>" + htmx.html.add_folder_dialog(
        fieldErrors = errors,
        formJson = jsonIn,
        parentId = parentId
      )
      dialogFormValidationResponse(payload)

    try apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, parentId = jsonIn(Api.Field.Folder.PARENT_ID).str)

    val folderName = jsonIn(Api.Field.Folder.NAME).str
    val parentId = jsonIn(Api.Field.Folder.PARENT_ID).str

    try App.altitude.service.folder.add(folderName, parentId = Some(parentId))
    catch
      // The name rule the stored name is held to, once normalized (`Validators.validName`)
      case ex: ValidationException => return responseWithValidationErrors(ex.errors.toMap, parentId = parentId)
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        return responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), parentId = parentId)
      case _: NotFoundException =>
        logger.debug(s"Not adding folder [$folderName]: parent [$parentId] is no folder of the repository")
        return responseWithValidationErrors(Map(Api.Field.Folder.PARENT_ID -> C.Msg.Err.PARENT_FOLDER_NOT_FOUND), parentId)

    // The client reloads the folder tree off the "folderAdded" event - no markup needed here
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  /** An unknown folder, or another repository's, is a 404, and the root folder a 409 */
  @requireLogin()
  @requirePermission(Permission.Organize)
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
      val payload = "<!doctype html>" + htmx.html.rename_folder_dialog(
        title = C.UI.RENAME_FOLDER_DIALOG_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        id = folderId
      )
      dialogFormValidationResponse(payload)

    try apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, folderId = jsonIn(Api.Field.ID).str)

    val newName = jsonIn(Api.Field.Folder.NAME).str
    val folderId = jsonIn(Api.Field.ID).str

    answering {
      try
        App.altitude.service.folder.rename(folderId = folderId, newName = newName)
        cask.Response(newName, 200, Seq(("Content-Type", "text/html")))
      catch
        // The name rule the stored name is held to, once normalized (`Validators.validName`)
        case ex: ValidationException => responseWithValidationErrors(ex.errors.toMap, folderId = folderId)
        case ex: DuplicateException =>
          val message = ex.message.getOrElse("Folder name already exists at this level")
          responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), folderId = folderId)
    }

  /**
   * Moves a folder under another. A folder that is not the repository's, unknown or another repository's, is a 404, and so is the
   * root folder of another repository; a destination that is not the repository's is a 400, as it does not exist.
   */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/move")
  def htmxMoveFolder(repoId: String, movedFolderId: String, newParentId: String)(using request: Request): Response[String] =
    logger.trace(s"Moving folder $movedFolderId to $newParentId")

    // short-circuit if this is a noop
    if movedFolderId == newParentId then return cask.Response("", 400, Seq(("Content-Type", "text/html")))

    answering {
      try
        App.altitude.service.folder.move(movedFolderId, newParentId)
        cask.Response("", 200, Seq(("Content-Type", "text/html")))
      catch
        case ex: DuplicateException =>
          cask.Response(ex.message.getOrElse("Folder name already exists at this level"), 409, Seq(("Content-Type", "text/html")))
    }

  /** An unknown folder, or another repository's, is a 404, and the root folder a 409 */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.delete(f"/$prefix/r/:repoId/")
  def htmxDeleteFolder(repoId: String, id: String)(using request: Request): Response[String] =
    answering {
      App.altitude.service.library.deleteFolderById(id)
      cask.Response("", 200, Seq(("Content-Type", "text/html")))
    }

  initialize()
