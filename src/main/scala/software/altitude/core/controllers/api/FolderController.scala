package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Folder

class FolderController extends BaseApiController {

  get() {
    val folders = app.service.folder.hierarchy()

    Ok(Json.obj(
      Api.Field.Folder.HIERARCHY -> folders.map(_.toJson)
    ))
  }

  get("/:id") {
    val id = realId(params.get(Api.Field.ID).get)
    val folder: Folder = app.service.folder.getById(id)

    Ok(Json.obj(
      Api.Field.Folder.FOLDER -> folder.toJson
    ))
  }

  get(s"/:${Api.Field.Folder.PARENT_ID}/children") {
    val parentId = realId(params.getAs[String](Api.Field.Folder.PARENT_ID).get)
    val allRepoFolders = app.service.folder.getAll
    val folders = app.service.folder.immediateChildren(parentId, allRepoFolders = allRepoFolders)
    val path = app.service.folder.pathComponents(parentId)

    Ok(Json.obj(
      Api.Field.Folder.PATH -> path.map(_.toJson),
      Api.Field.Folder.FOLDERS -> folders.map(_.toJson),
    ))
  }

  post("/") {
    val name = (unscrubbedReqJson.get \ Api.Field.Folder.NAME).as[String]
    val parentId = realId((unscrubbedReqJson.get \ Api.Field.Folder.PARENT_ID).as[String])

    val newFolder: Folder = app.service.library.addFolder(name = name, parentId = Some(parentId))
    logger.debug(s"New folder: $newFolder")

    Ok(Json.obj(Api.Field.Folder.FOLDER -> newFolder.toJson))
  }

  delete("/:id") {
    val id = realId(params.get(Api.Field.ID).get)
    logger.info(s"Deleting folder: $id")
    app.service.library.deleteFolderById(id)

    OK
  }

  put("/:id") {
    val id = realId(params.get(Api.Field.ID).get)
    logger.info(s"Updating folder: $id")
    val newName = (unscrubbedReqJson.get \ Api.Field.Folder.NAME).asOpt[String]
    val newParentId = (unscrubbedReqJson.get \ Api.Field.Folder.PARENT_ID).asOpt[String]

    if (newName.isDefined) {
      app.service.library.renameFolder(id, newName.get)
    }

    if (newParentId.isDefined) {
      app.service.library.moveFolder(id, newParentId.get)
    }

    OK
  }

  private def realId(aliasOrId: String): String = aliasOrId match {
    case FieldConst.Folder.Alias.ROOT => RequestContext.getRepository.rootFolderId
    case _ => aliasOrId
  }

}
