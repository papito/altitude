package altitude.core.routes.api

import altitude.core.Api
import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.Response
import org.slf4j.Logger
import play.api.libs.json.JsObject

class AssetController(using logger: Logger) extends BaseController:
  private val prefix = "api/asset"

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/move")
  def moveAssets(repoId: String)(using request: Request): Response[String] =
    val jsonIn: JsObject = unscrubbedJson.get
    val folderId = (jsonIn \ Api.Field.FOLDER_ID).as[String]
    val assetIdSet = (jsonIn \ Api.Field.ASSET_IDS).as[Seq[String]].toSet
    logger.info(s"Moving assets ${assetIdSet.mkString(", ")} to $folderId")
    App.altitude.service.library.moveAssetsToFolder(assetIdSet, folderId)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/move")
  def moveAssetsToTrash(repoId: String)(using request: Request): Response[String] =
    val jsonIn: JsObject = unscrubbedJson.get
    val assetIdSet = (jsonIn \ Api.Field.ASSET_IDS).as[Seq[String]].toSet
    logger.info(s"Recycling assets ${assetIdSet.mkString(", ")}")

    App.altitude.service.library.recycleAssets(assetIdSet)

    cask.Response("{}", 200, Seq(("Content-Type", "application/json")))

  initialize()
