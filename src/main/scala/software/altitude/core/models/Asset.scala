package software.altitude.core.models

import java.time.LocalDateTime
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.language.implicitConversions

/**
 * All asset-related metadata.
 *
 * Since we do not store actual binary data in a DB, the data itself is only passed via AssetWithData. This [underlying] class is
 * for passing around asset metadata.
 */
object Asset {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Asset] = Json.format[Asset]
  implicit def fromJson(json: JsValue): Asset = Json.fromJson[Asset](json).get

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

case class Asset(
    id: Option[String] = None,
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
    isRecycled: Boolean = false,
    isPipelineProcessed: Boolean = false,
    isInFaceRecModel: Boolean = false,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def toString: String =
    s"Asset: [$id] [$fileName] Recycled: [$isRecycled] Triaged: [$isTriaged] Type: [${assetType.mediaType}:${assetType.mediaSubtype}]"
}
