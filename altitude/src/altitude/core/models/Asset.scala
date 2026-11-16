package altitude.core.models

import java.time.LocalDateTime

import altitude.core.FieldConst
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.--
import altitude.core.util.JsonCodec.given

/** An asset's metadata. The file itself is never in the database and travels in [[AssetWithData]]. */
object Asset:
  given JsonCodec.ReadWriter[Asset] = JsonCodec.macroRW
  given Conversion[ujson.Value, Asset] = json => JsonCodec.read[Asset](json)

  def fromJson(json: ujson.Value): Asset = JsonCodec.read[Asset](json)

  def getPublicMetadata(extractedMetadata: ExtractedMetadata): PublicMetadata =
    PublicMetadata(
      // A phone video names its model in the QuickTime keys rather than EXIF
      deviceModel = extractedMetadata
        .getFieldValues("Exif IFD0")
        .get("Model")
        .orElse(extractedMetadata.getFieldValues("QuickTime Metadata").get("Model")),
      fNumber = extractedMetadata.getFieldValues("Exif SubIFD").get("F-Number"),
      focalLength = extractedMetadata.getFieldValues("Exif SubIFD").get("Focal Length"),
      iso = extractedMetadata.getFieldValues("Exif SubIFD").get("ISO Speed Ratings"),
      exposureTime = extractedMetadata.getFieldValues("Exif SubIFD").get("Exposure Time"),
      dateTimeOriginal = extractedMetadata.getFieldValues("Exif SubIFD").get("Date/Time Original")
    )

case class Asset(
    id: Option[String] = None,
    // The account that owns the asset: its importer, until that account is deleted and the asset moves to the deleting Admin
    ownerUserId: String,
    // The account that imported the asset, as it was then or as its last edit left it
    originalOwner: AccountSnapshot,
    assetType: AssetType,
    fileName: String,
    checksum: String,
    sizeBytes: Long,
    folderId: String,
    width: Int = 0,
    height: Int = 0,
    // How long a Video or an animated GIF plays; None for a still image
    durationMs: Option[Long] = None,
    userMetadata: UserMetadata = UserMetadata(),
    publicMetadata: PublicMetadata = PublicMetadata(),
    extractedMetadata: ExtractedMetadata = ExtractedMetadata(),
    isTriaged: Boolean = false,
    isRecycled: Boolean = false,
    isPipelineProcessed: Boolean = false,
    isInFaceRecModel: Boolean = false,
    originalCreatedAt: Option[LocalDateTime] = None,
    originalCreatedAtSource: Option[CaptureDateSource] = None,
    latitude: Option[Double] = None,
    longitude: Option[Double] = None,
    // Measured at import; None for a Video, an animated image, or a still image the pipeline did not measure
    imageQuality: Option[ImageQuality] = None,
    // The DCT perceptual hash of a still image; None as for the Image quality
    perceptualHash: Option[PerceptualHash] = None,
    // One per Scene model, for a still image the model could embed. Bulky, so only the Sequence candidates' read carries them
    sceneEmbeddings: SceneEmbeddings = SceneEmbeddings(),
    // Measured with the Image quality; None as for it. Bulky, so only Curation's read of a set's frames carries it
    histogram: Option[Histogram] = None,
    // The Sequence the image is in while in triage; None otherwise
    sequenceId: Option[String] = None,
    // The revision of the asset's edited images (the edited Preview and Display image), their URLs' cache key; 0 when it has
    // none, so the original's are shown
    editRev: Int = 0,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  /**
   * [[toJson]] for the browser, without the owner and importer record: no page reads them, and they would expose an account to
   * anyone who can see the Library
   */
  lazy val toBrowserJson: ujson.Obj = toJson -- (FieldConst.Asset.ORIGINAL_OWNER, FieldConst.Asset.OWNER_USER_ID)

  /** An image that is one picture: a Video is no image, and an animated image has a duration */
  def isStillImage: Boolean = assetType.mediaType == "image" && durationMs.isEmpty

  override def toString: String =
    s"Asset: [$id] [$fileName] Recycled: [$isRecycled] Triaged: [$isTriaged] Type: [${assetType.mediaType}:${assetType.mediaSubtype}]"
