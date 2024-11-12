package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions

/**
 * All asset-related metadata.
 *
 * Since we do not store actual data in DB, the data itself is only passed via AssetWithData.
 * This makes the purpose clear and avoids the confusion of an asset having zero bytes for data.
 */
object Asset {
  implicit def fromJson(json: JsValue): Asset = Asset(
      id = (json \ Field.ID).asOpt[String],
      userId = (json \ Field.USER_ID).as[String],
      assetType = (json \ Field.Asset.ASSET_TYPE).get,
      fileName = (json \ Field.Asset.FILENAME).as[String],
      folderId = (json \ Field.Asset.FOLDER_ID).as[String],
      checksum = (json \ Field.Asset.CHECKSUM).as[Int],
      sizeBytes = (json \ Field.Asset.SIZE_BYTES).as[Long],
      userMetadata = UserMetadata.fromJson((json \ Field.Asset.USER_METADATA).as[JsObject]),
      publicMetadata = PublicMetadata.fromJson((json \ Field.Asset.PUBLIC_METADATA).as[JsObject]),
      extractedMetadata = ExtractedMetadata.fromJson((json \ Field.Asset.EXTRACTED_METADATA).as[JsObject]),
      isTriaged = (json \ Field.Asset.IS_TRIAGED).as[Boolean],
      isRecycled = (json \ Field.Asset.IS_RECYCLED).as[Boolean]
    ).withCoreAttr(json)

  def getPublicMetadata(extractedMetadata: ExtractedMetadata): PublicMetadata = {
    PublicMetadata(
      deviceModel = extractedMetadata.getFieldValues("Exif IFD0").get("Model"),
      fNumber = extractedMetadata.getFieldValues("Exif SubIFD").get("F-Number"),
      focalLength = extractedMetadata.getFieldValues("Exif SubIFD").get("Focal Length"),
      iso = extractedMetadata.getFieldValues("Exif SubIFD").get("ISO Speed Ratings"),
      exposureTime = extractedMetadata.getFieldValues("Exif SubIFD").get("Exposure Time"),
      dateTimeOriginal = extractedMetadata.getFieldValues("Exif SubIFD").get("Date/Time Original")
    )
  }
}

case class Asset(id: Option[String] = None,
                 userId: String,
                 assetType: AssetType,
                 fileName: String,
                 checksum: Int,
                 sizeBytes: Long,
                 folderId: String,
                 userMetadata: UserMetadata = UserMetadata(),
                 publicMetadata: PublicMetadata = PublicMetadata(),
                 extractedMetadata: ExtractedMetadata = ExtractedMetadata(),
                 isTriaged: Boolean = false,
                 isRecycled: Boolean = false) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    Field.USER_ID -> userId,
    Field.Asset.FOLDER_ID -> folderId,
    Field.Asset.CHECKSUM -> checksum,
    Field.Asset.FILENAME -> fileName,
    Field.Asset.SIZE_BYTES -> sizeBytes,
    Field.Asset.ASSET_TYPE -> (assetType: JsValue),
    Field.Asset.USER_METADATA -> userMetadata.toJson,
    Field.Asset.PUBLIC_METADATA -> publicMetadata.toJson,
    Field.Asset.EXTRACTED_METADATA -> extractedMetadata.toJson,
    Field.Asset.IS_TRIAGED -> isTriaged,
    Field.Asset.IS_RECYCLED -> isRecycled
  ) ++ coreJsonAttrs

  override def toString: String =
    s"Asset: [$id] Recycled: [$isRecycled] Triaged: [$isTriaged] Type: [${assetType.mediaType}:${assetType.mediaSubtype}]"
}
