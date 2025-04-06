package software.altitude.core.controllers.htmx

import org.scalatra.Route

import software.altitude.core.Api
import software.altitude.core.DuplicateException
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

    logger.info(s"Moving asset $movedAssetId to $newParentId")

    // Call the movers
    try {
      app.service.library.moveAssetToFolder(movedAssetId, newParentId)
    } catch {
      case ex: DuplicateException =>
        halt(409, ex.message.getOrElse("Asset is already in the folder"))
    }

    halt(200)
  }

  val htmxMoveAssetToTrash: Route = delete("/r/:repoId/move") {
    val trashedAssetId = request.getParameter(Api.Field.ASSET_ID)

    logger.info(s"Moving asset $trashedAssetId to the Trash Bin")

    // Call the Sanitation
    try {
      app.service.library.recycleAsset(trashedAssetId)
    } catch {
      case _: DuplicateException =>
        halt(409, "Asset is already in the trash bin")
    }

    halt(200)
  }
}
