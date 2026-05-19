package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.models.Folder
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class FolderController(using logger: Logger) extends BaseController:
  private val prefix = "api/folder"

  /**
   * Returns the full (non-recycled) folder tree for the current repository as JSON.
   *
   * Response shape (recursive):
   * {
   *   "id": "uuid",
   *   "parentId": "uuid",
   *   "name": "folder name",
   *   "numOfChildren": <count of direct non-recycled children>,
   *   "isRoot": true | false,
   *   "children": [ ... ]
   * }
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tree")
  def getFolderTree(repoId: String)(using request: Request): Response[String] =
    val rootFolderId = RequestContext.getRepository.rootFolderId
    val json = buildFolderTreeJson(rootFolderId)
    cask.Response(json.toString, 200, Seq(("Content-Type", "application/json")))

  /** Recursively build a ujson tree. Uses getById for each level (for correct numOfChildren) + getChildren to list children. */
  private def buildFolderTreeJson(folderId: String): ujson.Obj =
    val folder = App.altitude.service.folder.getById(folderId)
    val children = App.altitude.service.folder.getChildren(folderId)
    val childJsons: List[ujson.Obj] = children.map(c => buildFolderTreeJson(c.persistedId))

    val childrenArr = ujson.Arr()
    childJsons.foreach(childrenArr.arr.addOne)

    ujson.Obj(
      "id" -> folder.persistedId,
      "parentId" -> folder.parentId,
      "name" -> folder.name,
      "numOfChildren" -> childJsons.length,
      "isRoot" -> (folder.persistedId == folder.parentId),
      "children" -> childrenArr
    )

  initialize()

