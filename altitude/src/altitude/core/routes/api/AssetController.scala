package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

class AssetController(using logger: Logger) extends BaseController:
  private val prefix = "api/asset"

  /** A destination that is no folder of the repository, unknown or another repository's, is a JSON 404 that moves nothing */
  @requireLogin()
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
  @cask.delete(f"/$prefix/r/:repoId/move")
  def moveAssetsToTrash(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Recycling assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.recycleAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  /** An ID that is no asset of the repository, unknown or another repository's, is a JSON 404 that restores nothing */
  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/restore")
  def restoreAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Restoring assets ${assetIdSet.mkString(", ")}")

    answeringJson(restoreResponse(App.altitude.service.library.restoreRecycledAssets(assetIdSet)))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/purge")
  def purgeSelectedAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.trace(s"Purging selected assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.purgeSelectedAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  initialize()
