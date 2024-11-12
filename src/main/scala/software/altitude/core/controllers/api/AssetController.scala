package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.NotFoundException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Asset
import software.altitude.core.models.MimedAssetData
import software.altitude.core.models.MimedPreviewData
import software.altitude.core.{Const => C}

class AssetController extends BaseApiController {

  private val assetIdValidator = ApiRequestValidator(
    required=List(Api.Field.Folder.ASSET_IDS)
  )


  get(s"/:${Api.Field.ID}") {
    val id = params.get(Api.Field.ID).get

    val asset: Asset = app.service.library.getById(id)

    Ok(Json.obj(
      Api.Field.Asset.ASSET -> asset.userMetadata.toJson
    ))
  }

  get(s"/:${Api.Field.ID}/data") {
    val id = params.get(Api.Field.ID).get

    try {
      val data: MimedAssetData = app.service.fileStore.getAssetById(id)
      this.contentType = data.mimeType
      data.data
    }
    catch {
      case _: NotFoundException => redirect("/i/1x1.png")
    }
  }

  post(s"/:${Api.Field.ID}/metadata/:${Api.Field.Asset.METADATA_FIELD_ID}") {
    val assetId = params.get(Api.Field.ID).get
    val fieldId = params.get(Api.Field.Asset.METADATA_FIELD_ID).get
    val newValue = (unscrubbedReqJson.get \ Api.Field.Metadata.VALUE).as[String]

    logger.info(s"Adding metadata value [$newValue] for field [$fieldId] on asset [$assetId]")

    app.service.library.addMetadataValue(assetId, fieldId, newValue)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      Api.Field.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  put(s"/:${Api.Field.ID}/metadata/value/:${Api.Field.Asset.METADATA_VALUE_ID}") {
    val assetId = params.get(Api.Field.ID).get
    val valueId = params.get(Api.Field.Asset.METADATA_VALUE_ID).get
    val newValue = (unscrubbedReqJson.get \ Api.Field.Metadata.VALUE).as[String]

    logger.info(s"Updating metadata value [$newValue] for value ID [$valueId] on asset [$assetId]")

    app.service.library.updateMetadataValue(assetId, valueId, newValue)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      Api.Field.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  delete(s"/:${Api.Field.ID}/metadata/value/:${Api.Field.Asset.METADATA_VALUE_ID}") {
    val assetId = params.get(Api.Field.ID).get
    val valueId = params.get(Api.Field.Asset.METADATA_VALUE_ID).get

    logger.info(s"Removing metadata value [$valueId] on asset [$assetId]")

    app.service.library.deleteMetadataValue(assetId, valueId)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      Api.Field.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  // FIXME: PUT
  post(s"/:${Api.Field.ID}/move/:${Api.Field.Asset.FOLDER_ID}") {
    val id = params.get(Api.Field.ID).get
    val folderId = params.get(Api.Field.Asset.FOLDER_ID).get
    logger.info(s"Moving asset $id to $folderId")

    app.service.library.moveAssetToFolder(id, folderId)

    OK
  }

  // FIXME: PUT
  post(s"/move/to/:${Api.Field.Asset.FOLDER_ID}") {
    val folderId = params.get(Api.Field.Asset.FOLDER_ID).get

    logger.info(s"Moving assets to $folderId")

    assetIdValidator.validate(unscrubbedReqJson.get)

    val assetIds = (unscrubbedReqJson.get \ Api.Field.Folder.ASSET_IDS).as[Set[String]]

    logger.debug(s"Assets to move: $assetIds")

    app.service.library.moveAssetsToFolder(assetIds, folderId)

    OK
  }

  get("/:id/preview") {
    val id = params(Api.Field.ID)

    try {
      val preview: MimedPreviewData = app.service.library.getPreview(id)
      this.contentType = preview.mimeType
      preview.data
    }
    catch {
      case _: NotFoundException => redirect("/i/1x1.png")
    }
  }

  override def logRequestStart(): Unit = {
    if (!request.getRequestURI.endsWith(s"/${C.DataStore.PREVIEW}")) {
      super.logRequestStart()
    }
  }

  override def logRequestEnd(): Unit = {
    if (!request.getRequestURI.endsWith(s"/${C.DataStore.PREVIEW}")) {
      super.logRequestEnd()
    }
  }
}
