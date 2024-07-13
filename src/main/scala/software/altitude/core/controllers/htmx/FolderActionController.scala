package software.altitude.core.controllers.htmx

import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Folder
import software.altitude.core.{DataScrubber, DuplicateException, RequestContext, ValidationException, Const => C}

/**
  @ /htmx/folder/
 */
class FolderActionController extends BaseHtmxController{

  before() {
    requireLogin()

    if (RequestContext.repository.value.isEmpty){
      throw new RuntimeException("Repository not set")
    }
  }

  val showAddFolderModal: Route = get("/modals/add-folder") {
    val parentId: String = params.get(C.Api.Folder.PARENT_ID).get

    ssp("htmx/add_folder_modal",
      "minWidth" -> C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
      "title" -> C.UI.ADD_FOLDER_MODAL_TITLE,
      C.Api.Folder.PARENT_ID -> parentId)
  }

  val showRenameFolderModal: Route = get("/modals/rename-folder") {
    val folderId: String = params.get(C.Api.ID).get

    val folder: Folder = app.service.folder.getById(folderId)

    ssp("htmx/rename_folder_modal",
      "minWidth" -> C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
      "title" -> C.UI.RENAME_FOLDER_MODAL_TITLE,
      C.Api.Folder.EXISTING_NAME -> Some(folder.name),
      C.Api.ID -> folderId)
  }

  val showDeleteFolderModal: Route = get("/modals/delete-folder") {
    val folderId: String = params.get(C.Api.ID).get

    val folder: Folder = app.service.folder.getById(folderId)

    ssp("htmx/delete_folder_modal",
      "minWidth" -> C.UI.DELETE_FOLDER_MODAL_MIN_WIDTH,
      "title" -> C.UI.DELETE_FOLDER_MODAL_TITLE,
      C.Api.Folder.FOLDER -> folder)
  }

  val showFolderContextMenu: Route = get("/context-menu") {
    val folderId: String = params.get("folderId").get

    ssp("htmx/folder_context_menu",
      "folderId" -> folderId)
  }

  val htmxAddFolder: Route = post("/add") {
    val dataScrubber = DataScrubber(
      trim = List(C.Api.Folder.NAME),
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(
        C.Api.Folder.NAME, C.Api.Folder.PARENT_ID),
      maxLengths = Map(
        C.Api.Folder.NAME -> C.Api.Constraints.MAX_FOLDER_NAME_LENGTH,
      ),
      minLengths = Map(
        C.Api.Folder.NAME -> C.Api.Constraints.MIN_FOLDER_NAME_LENGTH,
      ),
    )

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)

    def haltWithValidationErrors(errors: Map[String, String], parentId: String): Unit = {
      halt(200,
        ssp(
          "htmx/add_folder_modal",
          "minWidth" -> C.UI.ADD_FOLDER_MODAL_MIN_WIDTH,
          "title" -> C.UI.ADD_FOLDER_MODAL_TITLE,
          "fieldErrors" -> errors,
          "formJson" -> jsonIn,
          C.Api.Folder.PARENT_ID -> parentId
        ),
        // we want to change the folder modal to show the errors, not reload the folder list!
        headers=Map("HX-Retarget" -> "this", "HX-Reswap" -> "innerHTML")
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(
          validationException.errors.toMap,
          parentId = (jsonIn \ C.Api.Folder.PARENT_ID).as[String])
    }

    val folderName = (jsonIn \ C.Api.Folder.NAME).as[String]
    val parentId = (jsonIn \ C.Api.Folder.PARENT_ID).as[String]

    try {
      app.service.library.addFolder(folderName, parentId = Some(parentId))
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        haltWithValidationErrors(Map(C.Api.Folder.NAME -> message), parentId = parentId)
    }

    val childFolders: List[Folder] = app.service.folder.immediateChildren(parentId)

    halt(200,
      ssp("htmx/folder_children.ssp",
        "folders" -> childFolders)
    )
  }

  val htmxRenameFolder: Route = put("/rename") {
    val dataScrubber = DataScrubber(
      trim = List(C.Api.Folder.NAME),
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(
        C.Api.Folder.NAME, C.Api.ID),
      maxLengths = Map(
        C.Api.Folder.NAME -> C.Api.Constraints.MAX_FOLDER_NAME_LENGTH,
      ),
      minLengths = Map(
        C.Api.Folder.NAME -> C.Api.Constraints.MIN_REPOSITORY_NAME_LENGTH,
      ),
    )

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)

    def haltWithValidationErrors(errors: Map[String, String], folderId: String): Unit = {
      halt(200,
        ssp(
          "htmx/rename_folder_modal",
          "minWidth" -> C.UI.RENAME_FOLDER_MODAL_MIN_WIDTH,
          "title" -> C.UI.RENAME_FOLDER_MODAL_TITLE,
          "fieldErrors" -> errors,
          "formJson" -> jsonIn,
          C.Api.ID -> folderId
        ),
        // we want to change the folder modal to show the errors, not reload the folder list!
        headers=Map("HX-Retarget" -> "this", "HX-Reswap" -> "innerHTML")
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(
          validationException.errors.toMap,
          folderId = (jsonIn \ C.Api.ID).as[String])
    }

    val newName = (jsonIn \ C.Api.Folder.NAME).as[String]
    val folderId = (jsonIn \ C.Api.ID).as[String]

    try {
      app.service.library.renameFolder(folderId=folderId, newName=newName)
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Folder name already exists at this level")
        haltWithValidationErrors(Map(C.Api.Folder.NAME -> message), folderId = folderId)
    }

    halt(200, newName)
  }

  val htmxFolderChildren: Route = get("/children") {
    val parentId: String = params.get(C.Api.Folder.PARENT_ID).get
    val childFolders: List[Folder] = app.service.folder.immediateChildren(parentId)

    ssp("htmx/folder_children",
      "folders" -> childFolders)
  }

  val htmxMoveFolder: Route = post("/move") {
    val movedFolderId = request.getParameter(C.Api.Folder.MOVED_FOLDER_ID)
    val newParentId = request.getParameter(C.Api.Folder.NEW_PARENT_ID)

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

  val htmxDeleteFolder: Route = delete("/") {
    val folderId = params.get(C.Api.ID).get
    app.service.library.deleteFolderById(folderId)
    halt(200)
  }

}
