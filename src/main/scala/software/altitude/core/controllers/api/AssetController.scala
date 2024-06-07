package software.altitude.core.controllers.api

import org.scalatra.Ok
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import software.altitude.core.Const.Api
import software.altitude.core.NotFoundException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.Util
import software.altitude.core.models.Asset
import software.altitude.core.models.Data
import software.altitude.core.models.Preview
import software.altitude.core.{Const => C}

class AssetController extends BaseApiController {
  private final val log = LoggerFactory.getLogger(getClass)

  get(s"/:${C.Api.ID}") {
    val id = params.get(C.Api.ID).get

    val asset: Asset = app.service.library.getById(id)

    Ok(Json.obj(
      C.Api.Asset.ASSET -> Util.withFormattedMetadata(app, asset)
    ))
  }

  get(s"/:${C.Api.ID}/data") {
    val id = params.get(C.Api.ID).get

    try {
      val data: Data = app.service.fileStore.getById(id)
      this.contentType = data.mimeType
      data.data
    }
    catch {
      case _: NotFoundException => redirect("/i/1x1.png")
    }
  }

  post(s"/:${C.Api.ID}/metadata/:${C.Api.Asset.METADATA_FIELD_ID}") {
    val assetId = params.get(C.Api.ID).get
    val fieldId = params.get(C.Api.Asset.METADATA_FIELD_ID).get
    val newValue = (requestJson.get \ C.Api.Metadata.VALUE).as[String]

    log.info(s"Adding metadata value [$newValue] for field [$fieldId] on asset [$assetId]")

    app.service.library.addMetadataValue(assetId, fieldId, newValue)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      C.Api.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  put(s"/:${C.Api.ID}/metadata/value/:${C.Api.Asset.METADATA_VALUE_ID}") {
    val assetId = params.get(C.Api.ID).get
    val valueId = params.get(C.Api.Asset.METADATA_VALUE_ID).get
    val newValue = (requestJson.get \ C.Api.Metadata.VALUE).as[String]

    log.info(s"Updating metadata value [$newValue] for value ID [$valueId] on asset [$assetId]")

    app.service.library.updateMetadataValue(assetId, valueId, newValue)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      C.Api.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  delete(s"/:${C.Api.ID}/metadata/value/:${C.Api.Asset.METADATA_VALUE_ID}") {
    val assetId = params.get(C.Api.ID).get
    val valueId = params.get(C.Api.Asset.METADATA_VALUE_ID).get

    log.info(s"Removing metadata value [$valueId] on asset [$assetId]")

    app.service.library.deleteMetadataValue(assetId, valueId)

    val newMetadata = app.service.metadata.getMetadata(assetId)

    Ok(Json.obj(
      C.Api.Asset.METADATA -> app.service.metadata.toJson(newMetadata)
    ))
  }

  // FIXME: PUT
  post(s"/:${C.Api.ID}/move/:${C.Api.Asset.FOLDER_ID}") {
    val id = params.get(C.Api.ID).get
    val folderId = params.get(C.Api.Asset.FOLDER_ID).get
    log.info(s"Moving asset $id to $folderId")

    app.service.library.moveAssetToFolder(id, folderId)

    OK
  }

  // FIXME: PUT
  post(s"/move/to/:${C.Api.Asset.FOLDER_ID}") {
    val folderId = params.get(C.Api.Asset.FOLDER_ID).get

    log.info(s"Moving assets to $folderId")

    val validator = ApiRequestValidator(List(C.Api.Folder.ASSET_IDS))
    validator.validate(requestJson.get)

    val assetIds = (requestJson.get \ C.Api.Folder.ASSET_IDS).as[Set[String]]

    log.debug(s"Assets to move: $assetIds")

    app.service.library.moveAssetsToFolder(assetIds, folderId)

    OK
  }

  // FIXME: PUT
  post(s"/:${C.Api.ID}/move/to/triage") {
    val id = params.get(C.Api.ID).get
    log.info(s"Moving $id to TRIAGE")
    app.service.library.moveAssetToTriage(id)

    OK
  }

  // FIXME: PUT
  post("/move/to/triage") {
    log.info("Clearing category")

    val validator = ApiRequestValidator(List(C.Api.Folder.ASSET_IDS))
    validator.validate(requestJson.get)

    val assetIds = (requestJson.get \ C.Api.Folder.ASSET_IDS).as[Set[String]]

    log.debug(s"Assets to move to traige: $assetIds")

    app.service.library.moveAssetsToTriage(assetIds)

    OK
  }

  get("/:id/preview") {
    val id = params(Api.ID)

    try {
      val preview: Preview = app.service.library.getPreview(id)
      this.contentType = preview.mimeType
      preview.data
    }
    catch {
      case ex: NotFoundException => redirect("/i/1x1.png")
    }
  }

  override def logRequestStart(): Unit = {
    if (!request.getRequestURI.endsWith("/preview")) {
      super.logRequestStart()
    }
  }

  override def logRequestEnd(): Unit = {
    if (!request.getRequestURI.endsWith("/preview")) {
      super.logRequestEnd()
    }
  }
}
