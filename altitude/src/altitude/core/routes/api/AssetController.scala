package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

class AssetController(using logger: Logger) extends BaseController:
  private val prefix = "api/asset"

  /** A destination that is no folder of the repository, unknown or another repository's, is a JSON 404 that moves nothing */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/move")
  def moveAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val folderId = jsonIn(Api.Field.FOLDER_ID).str
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Moving assets ${assetIdSet.mkString(", ")} to $folderId")

    answeringJson {
      App.altitude.service.library.moveAssetsToFolder(assetIdSet, folderId)
      jsonResponse(ujson.Obj())
    }

  @requireLogin()
  @requirePermission(Permission.Recycle, Permission.CurateOwn, Permission.CurateAll)
  @cask.delete(f"/$prefix/r/:repoId/move")
  def moveAssetsToTrash(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Recycling assets ${assetIdSet.mkString(", ")}")

    answeringJson {
      App.altitude.service.library.recycleAssets(assetIdSet)
      jsonResponse(ujson.Obj())
    }

  /** An ID that is no asset of the repository, unknown or another repository's, is a JSON 404 that restores nothing */
  @requireLogin()
  @requirePermission(Permission.Recycle, Permission.CurateOwn, Permission.CurateAll)
  @cask.put(f"/$prefix/r/:repoId/restore")
  def restoreAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Restoring assets ${assetIdSet.mkString(", ")}")

    answeringJson(restoreResponse(App.altitude.service.library.restoreRecycledAssets(assetIdSet)))

  @requireLogin()
  @requirePermission(Permission.Purge)
  @cask.delete(f"/$prefix/r/:repoId/purge")
  def purgeSelectedAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Purging selected assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.purgeSelectedAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  initialize()
