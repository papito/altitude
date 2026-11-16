package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.models.Folder
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class FolderController(using logger: Logger) extends BaseController:
  private val prefix = "api/folder"

  /** The non-recycled folder tree; `numOfAssets` counts the sorted assets in a folder and every folder beneath it */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tree")
  def getFolderTree(repoId: String)(using request: Request): Response[String] =
    val json = toJson(App.altitude.service.folder.getTree)
    cask.Response(json.toString, 200, Seq(("Content-Type", "application/json")))

  // Hand-built rather than the model codec: the API is camelCase and `isRoot` is not a model field
  private def toJson(folder: Folder): ujson.Obj =
    ujson.Obj(
      "id" -> folder.persistedId,
      "parentId" -> folder.parentId,
      "name" -> folder.name,
      "numOfChildren" -> folder.numOfChildren,
      "numOfAssets" -> folder.numOfAssets,
      "isRoot" -> (folder.persistedId == folder.parentId),
      "children" -> ujson.Arr.from(folder.children.map(toJson))
    )

  initialize()
