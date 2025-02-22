package software.altitude.core.controllers.htmx

import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Api
import software.altitude.core.DataScrubber
import software.altitude.core.DuplicateException
import software.altitude.core.RequestContext
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Folder
import software.altitude.core.models.Repository
import software.altitude.core.{ Const => C }

/** @ /htmx/folder/ */
class FolderActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val showAddFolderModal: Route = get("/r/:repoId/modals/add-folder") {
    val parentId: String = params.get(Api.Field.Folder.PARENT_ID).get

    ssp(
      "htmx/add_folder_modal",
      Api.Modal.MIN_WIDTH -> C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.ADD_FOLDER_MODAL_TITLE,
      Api.Field.Folder.PARENT_ID -> parentId
    )
  }

  val showRenameFolderModal: Route = get("/r/:repoId/modals/rename-folder") {
    val folderId: String = params.get(Api.Field.ID).get

    val folder: Folder = app.service.folder.getById(folderId)

    ssp(
      "htmx/rename_folder_modal",
      Api.Modal.MIN_WIDTH -> C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.RENAME_FOLDER_MODAL_TITLE,
      Api.Field.Folder.EXISTING_NAME -> Some(folder.name),
      Api.Field.ID -> folderId
    )
  }

  val showDeleteFolderModal: Route = get("/r/:repoId/modals/delete-folder") {
    val folderId: String = params.get(Api.Field.ID).get

    val folder: Folder = app.service.folder.getById(folderId)

    ssp(
      "htmx/delete_folder_modal",
      Api.Modal.MIN_WIDTH -> C.UI.DELETE_FOLDER_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.DELETE_FOLDER_MODAL_TITLE,
      Api.Field.Folder.FOLDER -> folder
    )
  }

  val showFolderContextMenu: Route = get("/r/:repoId/context-menu") {
    val folderId: String = params.get("folderId").get

    ssp("htmx/folder_context_menu", Api.Field.FOLDER_ID -> folderId)
  }

  val showFoldersTab: Route = get("/r/:repoId/tab") {
    val repo: Repository = RequestContext.getRepository
    val rootFolder: Folder = app.service.folder.getById(repo.rootFolderId)

    ssp("htmx/folders", Api.Field.Folder.ROOT_FOLDER -> rootFolder)
  }

  val htmxAddFolder: Route = post("/r/:repoId/add") {
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

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)

    def haltWithValidationErrors(errors: Map[String, String], parentId: String): Unit = {
      halt(
        200,
        ssp(
          "htmx/add_folder_modal",
          Api.Modal.MIN_WIDTH -> C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
          Api.Modal.TITLE -> C.UI.ADD_FOLDER_MODAL_TITLE,
          Api.Modal.FIELD_ERRORS -> errors,
          Api.Modal.FORM_JSON -> jsonIn,
          Api.Field.Folder.PARENT_ID -> parentId
        ),
        // we want to change the folder modal to show the errors, not reload the folder list!
        headers = Map("HX-Retarget" -> "this", "HX-Reswap" -> "innerHTML")
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(validationException.errors.toMap, parentId = (jsonIn \ Api.Field.Folder.PARENT_ID).as[String])
    }

    val folderName = (jsonIn \ Api.Field.Folder.NAME).as[String]
    val parentId = (jsonIn \ Api.Field.Folder.PARENT_ID).as[String]

    try {
      app.service.library.addFolder(folderName, parentId = Some(parentId))
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        haltWithValidationErrors(Map(Api.Field.Folder.NAME -> message), parentId = parentId)
    }

    val childFolders: List[Folder] = app.service.folder.immediateChildren(parentId)

    halt(200, ssp("htmx/folder_children.ssp", Api.Field.Folder.FOLDERS -> childFolders))
  }

  val htmxRenameFolder: Route = put("/r/:repoId/rename") {
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

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)

    def haltWithValidationErrors(errors: Map[String, String], folderId: String): Unit = {
      halt(
        200,
        ssp(
          "htmx/rename_folder_modal",
          Api.Modal.MIN_WIDTH -> C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
          Api.Modal.TITLE -> C.UI.RENAME_FOLDER_MODAL_TITLE,
          Api.Modal.FIELD_ERRORS -> errors,
          Api.Modal.FORM_JSON -> jsonIn,
          Api.Field.ID -> folderId
        ),
        // we want to change the folder modal to show the errors, not reload the folder list!
        headers = Map("HX-Retarget" -> "this", "HX-Reswap" -> "innerHTML")
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(validationException.errors.toMap, folderId = (jsonIn \ Api.Field.ID).as[String])
    }

    val newName = (jsonIn \ Api.Field.Folder.NAME).as[String]
    val folderId = (jsonIn \ Api.Field.ID).as[String]

    try {
      app.service.library.renameFolder(folderId = folderId, newName = newName)
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        haltWithValidationErrors(Map(Api.Field.Folder.NAME -> message), folderId = folderId)
    }

    halt(200, newName)
  }

  val htmxFolderChildren: Route = get("/r/:repoId/children") {
    val parentId: String = params.get(Api.Field.Folder.PARENT_ID).get
    val childFolders: List[Folder] = app.service.folder.immediateChildren(parentId)

    ssp("htmx/folder_children", Api.Field.Folder.FOLDERS -> childFolders)
  }

  val htmxMoveFolder: Route = post("/r/:repoId/move") {
    val movedFolderId = request.getParameter(Api.Field.Folder.MOVED_FOLDER_ID)
    val newParentId = request.getParameter(Api.Field.Folder.NEW_PARENT_ID)

    logger.info(s"Moving folder $movedFolderId to $newParentId")

    // short-circuit if this is a noop
    if (movedFolderId == newParentId) {
      halt(400)
    }

    // Call the movers
    try {
      app.service.folder.move(movedFolderId, newParentId)
    } catch {
      case ex: DuplicateException =>
        halt(409, ex.message.getOrElse("Folder name already exists at this level"))
    }

    halt(200)
  }

  val htmxDeleteFolder: Route = delete("/r/:repoId/") {
    val folderId = params.get(Api.Field.ID).get
    app.service.library.deleteFolderById(folderId)
    halt(200)
  }

}
