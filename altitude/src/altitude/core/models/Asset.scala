package altitude.core.models

import altitude.core.json.UpickleConverters._
import java.time.LocalDateTime
import ujson.Value
import upickle.default.ReadWriter
import upickle.default.write
import upickle.default.writeJs

/**
 * All asset-related metadata.
 *
 * Since we do not store actual binary data in a DB, the data itself is only passed via AssetWithData. This [underlying] class is
 * for passing around asset metadata.
 */
object Asset {
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
    width: Int = 0,
    height: Int = 0,
    publicMetadata: PublicMetadata = PublicMetadata(),
    extractedMetadata: ExtractedMetadata = ExtractedMetadata(),
    isTriaged: Boolean = false,
    isRecycled: Boolean = false,
    isPipelineProcessed: Boolean = false,
    isInFaceRecModel: Boolean = false,
    originalCreatedAt: Option[LocalDateTime] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
  derives ReadWriter:
  def toJsonString: String = write(this)
  def toJson: Value = writeJs(this)

  override def toString: String =
    s"Asset: [$id] [$fileName] Recycled: [$isRecycled] Triaged: [$isTriaged] Type: [${assetType.mediaType}:${assetType.mediaSubtype}]"
