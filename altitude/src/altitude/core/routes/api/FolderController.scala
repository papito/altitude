package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.models.Folder
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class FolderController(using logger: Logger) extends BaseController:
  private val prefix = "api/folder"

  /**
   * Returns the full (non-recycled) folder tree for the current repository as JSON.
   *
   * Response shape (recursive): { "id": "uuid", "parentId": "uuid", "name": "folder name", "numOfChildren": <count of direct
   * non-recycled children>, "isRoot": true | false, "children": [ ... ] }
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tree")
  def getFolderTree(repoId: String)(using request: Request): Response[String] =
    val rootFolderId = RequestContext.getRepository.rootFolderId

    // One query for all folders in the repository - the tree is assembled in memory
    val folders = App.altitude.service.folder.getAll.filterNot(_.isRecycled)

    val rootFolder = folders
      .find(_.persistedId == rootFolderId)
      .getOrElse(throw NotFoundException(s"Root folder $rootFolderId not found"))

    // The root folder is its own parent - keep it out of the index so it does not become its own child
    val childrenByParentId: Map[String, List[Folder]] =
      folders.filter(f => f.persistedId != f.parentId).groupBy(_.parentId)

    val json = buildFolderTreeJson(rootFolder, childrenByParentId)
    cask.Response(json.toString, 200, Seq(("Content-Type", "application/json")))

  private def buildFolderTreeJson(folder: Folder, childrenByParentId: Map[String, List[Folder]]): ujson.Obj =
    val children = childrenByParentId
      .getOrElse(folder.persistedId, Nil)
      .sortBy(_.nameLowercase)
    val childJsons = children.map(buildFolderTreeJson(_, childrenByParentId))

    ujson.Obj(
      "id" -> folder.persistedId,
      "parentId" -> folder.parentId,
      "name" -> folder.name,
      "numOfChildren" -> childJsons.length,
      "isRoot" -> (folder.persistedId == folder.parentId),
      "children" -> ujson.Arr.from(childJsons)
    )

  initialize()
