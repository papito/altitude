package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.{ convertToStringShouldWrapperForVerb, should }

import scala.language.implicitConversions

import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Face
import altitude.core.models.Folder
import altitude.core.models.Stat
import altitude.core.models.UserMetadataValue

@DoNotDiscover class CoreModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Serialize and deserialize a Stat model") {

    /**
     * Setup:
     *
     * A Stat with a dimension name and a value.
     *
     * Assertions:
     *
     * The Stat survives a round trip through JSON with both fields intact.
     */
    val stat = Stat(dimension = "test_dim", dimVal = 42)
    val json = stat.toJson
    val deserialized: Stat = json
    deserialized.dimension should be(stat.dimension)
    deserialized.dimVal should be(stat.dimVal)
  }

  test("Serialize and deserialize a Folder model") {

    /**
     * Setup:
     *
     * A Folder with an ID, a parent ID and a name.
     *
     * Assertions:
     *
     * The Folder survives a round trip through JSON with its ID, name and parent intact.
     */
    val folder = Folder(id = Some("test-id"), parentId = "parent-id", name = "Test Folder")
    val json = folder.toJson
    val deserialized: Folder = json
    deserialized.id should be(folder.id)
    deserialized.name should be(folder.name)
    deserialized.parentId should be(folder.parentId)
  }

  test("Video duration and Face Frame time, quality and enrollment travel through JSON, and the times are absent when unset") {

    /**
     * Setup:
     *
     * A video asset with a 90.5 s duration and a copy of it as an image with none, plus a Face from a video Frame at 1.5 s with a
     * quality and an enrollment flag, and a copy of the Face with no Frame time.
     *
     * Assertions:
     *
     * The duration and the Frame time are written to JSON under their snake_case names and read back, and the image's JSON has no
     * duration key at all. The Face's quality and enrollment flag also travel through JSON.
     */
    val video = Asset(
      userId = "u",
      assetType = AssetType("video", "mp4", "video/mp4"),
      fileName = "clip.mp4",
      checksum = 1,
      sizeBytes = 10,
      folderId = "",
      durationMs = Some(90_500L))
    video.toJson("duration_ms").num should be(90500)
    Asset.fromJson(video.toJson).durationMs should be(Some(90_500L))
    val image = video.copy(assetType = AssetType("image", "png", "image/png"), durationMs = None)
    image.toJson.value.contains("duration_ms") should be(false)
    Asset.fromJson(image.toJson).durationMs should be(None)

    val face = Face(
      x1 = 1,
      y1 = 2,
      width = 3,
      height = 4,
      detectionScore = 0.5,
      checksum = 7,
      features = Array(0.1f),
      quality = 21.5,
      isEnrolled = false,
      frameTimeMs = Some(1500L))
    face.toJson("frame_time_ms").num should be(1500)
    (face.toJson: Face).frameTimeMs should be(Some(1500L))
    (face.copy(frameTimeMs = None).toJson: Face).frameTimeMs should be(None)
    face.toJson("quality").num should be(21.5)
    face.toJson("is_enrolled").bool should be(false)
    (face.toJson: Face).isEnrolled should be(false)
  }

  test("The device model comes from EXIF, or from a phone video's QuickTime keys when EXIF has none") {

    /**
     * Setup:
     *
     * Extracted metadata with a model only in the QuickTime keys, the same with an EXIF model added, and empty metadata.
     *
     * Assertions:
     *
     * The public device model is the EXIF one when present, falls back to the QuickTime one, and is absent when neither exists.
     */
    val quickTime = ExtractedMetadata(Map("QuickTime Metadata" -> Map("Model" -> "iPhone 15")))
    Asset.getPublicMetadata(quickTime).deviceModel should be(Some("iPhone 15"))
    val both = ExtractedMetadata(quickTime.data + ("Exif IFD0" -> Map("Model" -> "NIKON D90")))
    Asset.getPublicMetadata(both).deviceModel should be(Some("NIKON D90"))
    Asset.getPublicMetadata(ExtractedMetadata()).deviceModel should be(None)
  }

  test("Faces with the same ID are equal and hash alike") {

    /**
     * Setup:
     *
     * Two Faces with one ID but different boxes and scores, and a third Face with another ID.
     *
     * Assertions:
     *
     * The first two are equal and share a hash code, and the third equals neither.
     */
    val face = Face(
      id = Some("1"),
      x1 = 1,
      y1 = 2,
      width = 3,
      height = 4,
      detectionScore = 0.5,
      checksum = 7,
      features = Array(0.1f),
      quality = 21.5,
      isEnrolled = false)
    val sameId = face.copy(x1 = 10, detectionScore = 0.9)

    sameId should be(face)
    sameId.hashCode should be(face.hashCode)
    (face.copy(id = Some("2")) == face) should be(false)
  }

  test("Metadata values that differ only in letter case are equal and hash alike") {

    /**
     * Setup:
     *
     * The values "Beach", "BEACH" (saved, with an ID) and "Lake".
     *
     * Assertions:
     *
     * "Beach" and "BEACH" are equal and share a hash code, and "Lake" equals neither.
     */
    val value = UserMetadataValue(value = "Beach")
    val upper = UserMetadataValue(id = Some("1"), value = "BEACH")

    upper should be(value)
    upper.hashCode should be(value.hashCode)
    (UserMetadataValue(value = "Lake") == value) should be(false)
  }
}
