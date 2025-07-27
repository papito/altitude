package software.altitude.core.controllers.htmx

import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Api
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Asset

/** @ /htmx/asset/ */
class AssetActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val showAssetDetailModal: Route = get("/r/:repoId/modals/asset-detail/:assetId") {
    val assetId: String = params.get(Api.Field.ASSET_ID).get

    val asset: Asset = app.service.asset.getById(assetId)

    ssp("htmx/view_image_detail_modal", Api.Field.Asset.ASSET -> asset)
  }

  val htmxMoveAsset: Route = put("/r/:repoId/move") {
    val movedAssetId = request.getParameter(Api.Field.ASSET_ID)
    val newParentId = request.getParameter(Api.Field.FOLDER_ID)

    if (movedAssetId != null) {
      logger.info(s"Moving asset $movedAssetId to $newParentId")
      app.service.library.moveAssetsToFolder(Set(movedAssetId), newParentId)
      halt(200)
    }

    if (request.getContentType.contains("application/json")) {
      val jsonIn: JsObject = unscrubbedReqJson.get
      val folderId = (jsonIn \ Api.Field.FOLDER_ID).as[String]
      val assetIdSet = (jsonIn \ Api.Field.ASSET_IDS).as[Seq[String]].toSet
      logger.info(s"Moving assets ${assetIdSet.mkString(", ")} to $folderId")
      app.service.library.moveAssetsToFolder(assetIdSet, folderId)
      halt(200)
    }
  }

  val htmxMoveAssetToTrash: Route = delete("/r/:repoId/move") {
    val trashedAssetId = request.getParameter(Api.Field.ASSET_ID)

    logger.info(s"Moving asset $trashedAssetId to the Trash Bin")

    app.service.library.recycleAssets(Set(trashedAssetId))
    halt(200)
  }
}
