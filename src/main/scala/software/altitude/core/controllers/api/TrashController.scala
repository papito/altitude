package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.FieldConst
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Asset
import software.altitude.core.util.Query

class TrashController extends BaseApiController {

  post("/recycle/:id") {
    val id = params.get(Api.Field.ID).get
    logger.info(s"Moving $id to TRASH")
    app.service.library.recycleAsset(id)

    OK
  }

  post("/recycle") {
    logger.info("Deleting assets")

    val validator = ApiRequestValidator(
      required = List(Api.Field.Folder.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Field.Folder.ASSET_IDS).as[Set[String]]
    logger.debug(s"Assets to move to trash: $assetIds")

    app.service.library.recycleAssets(assetIds)

    OK
  }

  post(s"/:id/move/:${FieldConst.Asset.FOLDER_ID}") {
    val id = params.get(Api.Field.ID).get
    val folderId = params.get(Api.Field.Asset.FOLDER_ID).get
    logger.info(s"Moving recycled asset $id to $folderId")

    app.service.library.moveAssetToFolder(id, folderId)

    OK
  }

  post(s"/move/to/:${Api.Field.Asset.FOLDER_ID}") {
    val folderId = params.get(Api.Field.Asset.FOLDER_ID).get
    logger.info(s"Moving recycled assets to $folderId")

    val validator = ApiRequestValidator(
      required = List(Api.Field.Trash.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Field.Trash.ASSET_IDS).as[Set[String]]
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
      required = List(Api.Field.Trash.ASSET_IDS)
    )

    validator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Field.Trash.ASSET_IDS).as[Set[String]]
    logger.debug(s"Recycled assets to restore: $assetIds")

    app.service.library.restoreRecycledAssets(assetIds)

    OK
  }

  get(s"/p/:${Api.Field.Search.PAGE}/rpp/:${Api.Field.Search.RESULTS_PER_PAGE}") {
    val rpp = params.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = params.getOrElse(Api.Field.Search.PAGE, "1").toInt

    // FIXME: use search() not query()
    val q = new Query(rpp = rpp, page = page)

    val results = app.service.library.queryRecycled(q)

    Ok(
      Json.obj(
        Api.Field.Search.ASSETS -> results.records.map {
          x =>
            val asset = x: Asset
            asset.userMetadata.toJson
        },
        Api.Field.TOTAL_RECORDS -> results.total,
        Api.Field.CURRENT_PAGE -> q.page,
        Api.Field.TOTAL_PAGES -> results.totalPages,
        Api.Field.RESULTS_PER_PAGE -> q.rpp
      ))
  }
}
