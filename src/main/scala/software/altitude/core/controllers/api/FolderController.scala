package software.altitude.core.controllers.api

import org.scalatra.Ok
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import software.altitude.core.RequestContext
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Folder
import software.altitude.core.{Const => C}

class FolderController extends BaseApiController {
  private final val log = LoggerFactory.getLogger(getClass)

  get() {
    val folders = app.service.folder.hierarchy()

    Ok(Json.obj(
      C.Api.Folder.HIERARCHY -> folders.map(_.toJson)
    ))
  }

  get("/:id") {
    val id = realId(params.get(C.Api.ID).get)
    val folder: Folder = app.service.folder.getById(id)

    Ok(Json.obj(
      C.Api.Folder.FOLDER -> folder.toJson
    ))
  }

  get(s"/:${C.Api.Folder.PARENT_ID}/children") {
    val parentId = realId(params.getAs[String](C.Api.Folder.PARENT_ID).get)
    val allRepoFolders = app.service.folder.getAll
    val folders = app.service.folder.immediateChildren(parentId, allRepoFolders = allRepoFolders)
    val path = app.service.folder.pathComponents(parentId)

    Ok(Json.obj(
      C.Api.Folder.PATH -> path.map(_.toJson),
      C.Api.Folder.FOLDERS -> folders.map(_.toJson),
    ))
  }

  post("/") {
    val name = (unscrubbedReqJson.get \ C.Api.Folder.NAME).as[String]
    val parentId = realId((unscrubbedReqJson.get \ C.Api.Folder.PARENT_ID).as[String])

    val newFolder: Folder = app.service.library.addFolder(name = name, parentId = Some(parentId))
    log.debug(s"New folder: $newFolder")

    Ok(Json.obj(C.Api.Folder.FOLDER -> newFolder.toJson))
  }

  delete("/:id") {
    val id = realId(params.get(C.Api.ID).get)
    log.info(s"Deleting folder: $id")
    app.service.library.deleteFolderById(id)

    OK
  }

  put("/:id") {
    val id = realId(params.get(C.Api.ID).get)
    log.info(s"Updating folder: $id")
    val newName = (unscrubbedReqJson.get \ C.Api.Folder.NAME).asOpt[String]
    val newParentId = (unscrubbedReqJson.get \ C.Api.Folder.PARENT_ID).asOpt[String]

    if (newName.isDefined) {
      app.service.library.renameFolder(id, newName.get)
    }

    if (newParentId.isDefined) {
      app.service.library.moveFolder(id, newParentId.get)
    }

    OK
  }

  private def realId(aliasOrId: String): String = aliasOrId match {
    case C.Folder.Alias.ROOT => RequestContext.repository.value.get.rootFolderId
    case _ => aliasOrId
  }

}
