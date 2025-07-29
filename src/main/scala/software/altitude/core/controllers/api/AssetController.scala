package software.altitude.core.controllers.api

import org.scalatra.Route
import play.api.libs.json.JsObject

import software.altitude.core.Api
import software.altitude.core.controllers.BaseApiController

/** @ /api/asset/ */
class AssetController extends BaseApiController {
  before() {
    requireLogin()
  }

  val apiMoveAsset: Route = put("/r/:repoId/move") {
    val jsonIn: JsObject = unscrubbedReqJson.get
    val folderId = (jsonIn \ Api.Field.FOLDER_ID).as[String]
    val assetIdSet = (jsonIn \ Api.Field.ASSET_IDS).as[Seq[String]].toSet
    logger.info(s"Moving assets ${assetIdSet.mkString(", ")} to $folderId")
    app.service.library.moveAssetsToFolder(assetIdSet, folderId)

    halt(200, OK)
  }

  val apiMoveAssetToTrash: Route = delete("/r/:repoId/move") {
    val jsonIn: JsObject = unscrubbedReqJson.get
    val assetIdSet = (jsonIn \ Api.Field.ASSET_IDS).as[Seq[String]].toSet
    logger.info(s"Recycling assets ${assetIdSet.mkString(", ")}")

    app.service.library.recycleAssets(assetIdSet)

    halt(200, OK)
  }
}
