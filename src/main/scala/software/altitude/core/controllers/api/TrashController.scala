package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.Json
import software.altitude.core.Const.Api
import software.altitude.core.Const.Api.Folder
import software.altitude.core.Const.Api.Search
import software.altitude.core.Const.Api.Trash
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Asset
import software.altitude.core.util.Query
import software.altitude.core.{Const => C}

class TrashController extends BaseApiController {

  post("/recycle/:id") {
    val id = params.get(Api.ID).get
    logger.info(s"Moving $id to TRASH")
    app.service.library.recycleAsset(id)

    OK
  }

  post("/recycle") {
    logger.info("Deleting assets")

    val validator = ApiRequestValidator(
      required=List(Folder.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Folder.ASSET_IDS).as[Set[String]]
    logger.debug(s"Assets to move to trash: $assetIds")

    app.service.library.recycleAssets(assetIds)

    OK
  }

  post(s"/:id/move/:${C.Asset.FOLDER_ID}") {
    val id = params.get(C.Api.ID).get
    val folderId = params.get(Api.Asset.FOLDER_ID).get
    logger.info(s"Moving recycled asset $id to $folderId")

    app.service.library.moveAssetToFolder(id, folderId)

    OK
  }

  post(s"/move/to/:${Api.Asset.FOLDER_ID}") {
    val folderId = params.get(Api.Asset.FOLDER_ID).get
    logger.info(s"Moving recycled assets to $folderId")

    val validator = ApiRequestValidator(
      required=List(Trash.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Trash.ASSET_IDS).as[Set[String]]
    logger.debug(s"Recycled assets to move: $assetIds")

    app.service.library.moveAssetsToFolder(assetIds, folderId)

    OK
  }

  post("/:id/restore") {
    val assetId = params.get("id").get
    logger.info(s"Restoring asset $assetId")

    app.service.library.restoreRecycledAsset(assetId)

    OK
  }

  post("/restore") {
    logger.info("Restoring multiple assets")

    val validator = ApiRequestValidator(
      required=List(Api.Trash.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Trash.ASSET_IDS).as[Set[String]]
    logger.debug(s"Recycled assets to restore: $assetIds")

    app.service.library.restoreRecycledAssets(assetIds)

    OK
  }

  get(s"/p/:${Search.PAGE}/rpp/:${Api.Search.RESULTS_PER_PAGE}") {
    val rpp = params.getOrElse(Api.Search.RESULTS_PER_PAGE, C.Api.Search.DEFAULT_RPP.toString).toInt
    val page = params.getOrElse(C.Api.Search.PAGE, "1").toInt

    // FIXME: use search() not query()
    val q = new Query(rpp = rpp, page = page)

    val results = app.service.library.queryRecycled(q)

    Ok(Json.obj(
      C.Api.Search.ASSETS -> results.records.map { x =>
        val asset = x: Asset
        asset.metadata.toJson

      },
      C.Api.TOTAL_RECORDS -> results.total,
      C.Api.CURRENT_PAGE -> q.page,
      C.Api.TOTAL_PAGES -> results.totalPages,
      C.Api.RESULTS_PER_PAGE -> q.rpp
    ))
  }
}
