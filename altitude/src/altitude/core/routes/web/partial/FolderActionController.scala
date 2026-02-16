package altitude.core.routes.web.partial

import altitude.core.{Api, App, DataScrubber, DuplicateException, RequestContext, ValidationException, Const => C}
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.{Folder, Repository}
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.api.libs.json.JsObject

class FolderActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/folder"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/add-folder")
  def showAddFolderModal(repoId: String, parentId: String, minWidth: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.add_folder_modal(
      minWidth = C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
      title = C.UI.ADD_FOLDER_MODAL_TITLE,
      parentId = parentId
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/rename-folder")
  def showRenameFolderModal(repoId: String, id: String, minWidth: String)(using request: Request): Response[String] =
    val folder: Folder = App.altitude.service.folder.getById(id)
    val payload = "<!doctype html>" + htmx.html.rename_folder_modal(
      minWidth = C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
      title = C.UI.RENAME_FOLDER_MODAL_TITLE,
      existingName = Some(folder.name),
      id = id
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/delete-folder")
  def showDeleteFolderModal(repoId: String, id: String, minWidth: String)(using request: Request): Response[String] =
    val folder: Folder = App.altitude.service.folder.getById(id)
    val payload = "<!doctype html>" + htmx.html.delete_folder_modal(
      minWidth = C.UI.DELETE_FOLDER_MODAL_MIN_WIDTH,
      title = C.UI.DELETE_FOLDER_MODAL_TITLE,
      folder = folder
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/context-menu")
  def showFolderContextMenu(repoId: String, folderId: String)(using request: Request): Response[String] =
    val payload = "<!doctype html>" + htmx.html.folder_context_menu(folderId = folderId)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showFoldersTab(repoId: String)(using request: Request): Response[String] =
    val repo: Repository = RequestContext.getRepository
    val rootFolder: Folder = App.altitude.service.folder.getById(repo.rootFolderId)
    val payload = "<!doctype html>" + htmx.html.folders(rootFolder = rootFolder)
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

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String], parentId: String): Response[String] =
      val payload = "<!doctype html>" + htmx.html.add_folder_modal(
        minWidth = C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
        title = C.UI.ADD_FOLDER_MODAL_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        parentId = parentId
      )
      // we want to change the folder modal to show the errors, not reload the folder list!
      cask.Response(payload, 200, Seq(
        ("Content-Type", "text/html"),
        ("HX-Retarget", "this"),
        ("HX-Reswap", "innerHTML")
      ))

    try
      apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, parentId = (jsonIn \ Api.Field.Folder.PARENT_ID).as[String])

    val folderName = (jsonIn \ Api.Field.Folder.NAME).as[String]
    val parentId = (jsonIn \ Api.Field.Folder.PARENT_ID).as[String]

    try
      App.altitude.service.folder.add(folderName, parentId = Some(parentId))
    catch
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        return responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), parentId = parentId)

    val childFolders: List[Folder] = App.altitude.service.folder.getChildren(parentId)
    val payload = "<!doctype html>" + htmx.html.folder_children(folders = childFolders)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

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

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedJson.get)

    def responseWithValidationErrors(errors: Map[String, String], folderId: String): Response[String] =
      val payload = "<!doctype html>" + htmx.html.rename_folder_modal(
        minWidth = C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
        title = C.UI.RENAME_FOLDER_MODAL_TITLE,
        fieldErrors = errors,
        formJson = jsonIn,
        id = folderId
      )
      // we want to change the folder modal to show the errors, not reload the folder list!
      cask.Response(payload, 200, Seq(
        ("Content-Type", "text/html"),
        ("HX-Retarget", "this"),
        ("HX-Reswap", "innerHTML")
      ))

    try
      apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap, folderId = (jsonIn \ Api.Field.ID).as[String])

    val newName = (jsonIn \ Api.Field.Folder.NAME).as[String]
    val folderId = (jsonIn \ Api.Field.ID).as[String]

    try
      App.altitude.service.folder.rename(folderId = folderId, newName = newName)
    catch
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        return responseWithValidationErrors(Map(Api.Field.Folder.NAME -> message), folderId = folderId)

    cask.Response(newName, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/children")
  def htmxFolderChildren(repoId: String, parentId: String)(using request: Request): Response[String] =
    val childFolders: List[Folder] = App.altitude.service.folder.getChildren(parentId)
    val payload = "<!doctype html>" + htmx.html.folder_children(folders = childFolders)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/move")
  def htmxMoveFolder(repoId: String, movedFolderId: String, newParentId: String)(using request: Request): Response[String] =
    logger.info(s"Moving folder $movedFolderId to $newParentId")

    // short-circuit if this is a noop
    if movedFolderId == newParentId then
      return cask.Response("", 400, Seq(("Content-Type", "text/html")))

    // Call the movers
    try
      App.altitude.service.folder.move(movedFolderId, newParentId)
    catch
      case ex: DuplicateException =>
        return cask.Response(ex.message.getOrElse("Folder name already exists at this level"), 409, Seq(("Content-Type", "text/html")))

    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/")
  def htmxDeleteFolder(repoId: String, id: String)(using request: Request): Response[String] =
    App.altitude.service.library.deleteFolderById(id)
    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  initialize()


