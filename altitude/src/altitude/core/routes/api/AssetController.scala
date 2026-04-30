package altitude.core.routes.api

import altitude.core.Api
import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.Response
import org.slf4j.Logger

class AssetController(using logger: Logger) extends BaseController:
  private val prefix = "api/asset"

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/move")
  def moveAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val folderId = jsonIn(Api.Field.FOLDER_ID).str
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.info(s"Moving assets ${assetIdSet.mkString(", ")} to $folderId")
    App.altitude.service.library.moveAssetsToFolder(assetIdSet, folderId)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/move")
  def moveAssetsToTrash(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.info(s"Recycling assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.recycleAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/restore")
  def restoreAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.info(s"Restoring assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.restoreRecycledAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/purge")
  def purgeSelectedAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val assetIdSet = jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet
    logger.info(s"Purging selected assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.purgeSelectedAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  initialize()
