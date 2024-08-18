package software.altitude.core.models

import play.api.libs.json._
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

/**
 * All asset-related metadata.
 *
 * Since we do not store actual data in DB, the data itself is only passed via AssetWithData.
 *
 * This makes the purpose clear and avoids the confusion of an asset having zero byte data.
 */
object Asset {
  implicit def fromJson(json: JsValue): Asset = Asset(
      id = (json \ C.Base.ID).asOpt[String],
      userId = (json \ C.Base.USER_ID).as[String],
      assetType = (json \ C.Asset.ASSET_TYPE).get,
      fileName = (json \ C.Asset.FILENAME).as[String],
      folderId = (json \ C.Asset.FOLDER_ID).as[String],
      checksum = (json \ C.Asset.CHECKSUM).as[Int],
      sizeBytes = (json \ C.Asset.SIZE_BYTES).as[Long],
      metadata = Metadata.fromJson((json \ C.Asset.METADATA).as[JsObject]),
      extractedMetadata = Metadata.fromJson((json \ C.Asset.EXTRACTED_METADATA).as[JsObject]),
      isTriaged = (json \ C.Asset.IS_TRIAGED).as[Boolean],
      isRecycled = (json \ C.Asset.IS_RECYCLED).as[Boolean]
    ).withCoreAttr(json)
}

case class Asset(id: Option[String] = None,
                 userId: String,
                 assetType: AssetType,
                 fileName: String,
                 checksum: Int,
                 sizeBytes: Long,
                 folderId: String,
                 metadata: Metadata = Metadata(),
                 isTriaged: Boolean = false,
                 isRecycled: Boolean = false,
                 extractedMetadata: Metadata = Metadata()) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    C.Base.USER_ID -> userId,
    C.Asset.FOLDER_ID -> folderId,
    C.Asset.CHECKSUM -> checksum,
    C.Asset.FILENAME -> fileName,
    C.Asset.SIZE_BYTES -> sizeBytes,
    C.Asset.ASSET_TYPE -> (assetType: JsValue),
    C.Asset.METADATA -> metadata.toJson,
    C.Asset.EXTRACTED_METADATA -> extractedMetadata.toJson,
    C.Asset.IS_TRIAGED -> isTriaged,
    C.Asset.IS_RECYCLED -> isRecycled
  ) ++ coreJsonAttrs

  override def toString: String =
    s"Asset: [$id] Recycled: [$isRecycled] Triaged: [$isTriaged] Type: [${assetType.mediaType}:${assetType.mediaSubtype}]"
}
