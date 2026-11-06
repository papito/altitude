package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.models.CaptureDateSource
import altitude.core.models.Histogram
import altitude.core.models.MimedPreviewData
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.util.ImageMeasurement
import altitude.core.util.ImageUtil

@DoNotDiscover class AssetImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore with BurstFrames {
  import BurstFrames.*

  /** A synthetic unit vector of a Scene model's dimensions, of values no shorter type than float32 keeps */
  private def unitVector(model: SceneModel, seed: Int): SceneEmbedding = {
    val raw = Array.tabulate(model.dimensions)(i => math.sin(seed + i.toDouble).toFloat)
    val norm = math.sqrt(raw.map(v => v.toDouble * v).sum).toFloat
    SceneEmbedding(IArray.unsafeFromArray(raw.map(_ / norm)))
  }

  /**
   * The two columns of the asset through a raw select, each in a form that compares by value: SQLite's BLOB bytes as a sequence,
   * PostgreSQL's vector as its text; None for NULL
   */
  private def rawEmbeddingColumns(assetId: String): (Option[Any], Option[Any]) = {
    val row = testApp.txManager.asReadOnly {
      query("SELECT dinov2_embedding, clip_embedding FROM asset WHERE id = ?", assetId).head
    }
    def comparable(value: AnyRef): Option[Any] = Option(value).map {
      case bytes: Array[Byte] => bytes.toSeq
      case other => other.toString
    }
    (comparable(row("dinov2_embedding")), comparable(row("clip_embedding")))
  }

  test("Import duplicate") {

    /**
     * Setup:
     *
     * The same JPEG (images/2.jpg) imported twice.
     *
     * Assertions:
     *
     * The second import is rejected as a duplicate, and its staged copy is discarded instead of being left in staging.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/2.jpg")
    testApp.service.library.addImportAsset(importAsset)

    intercept[DuplicateException] {
      testApp.service.library.addImportAsset(importAsset)
    }

    // The duplicate's staged copy is discarded
    stagedFiles shouldBe empty
  }

  test("An import stages a copy of the file, then renames it into the store under the asset's ID") {

    /**
     * Setup:
     *
     * One JPEG (images/2.jpg) imported from its fixture path.
     *
     * Assertions:
     *
     * The source file is left where it was, staging ends up empty, and the file stored under the asset's ID is a byte-for-byte
     * copy of the source, of the size recorded on the asset.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/2.jpg")
    val imported: Asset = testApp.service.library.addImportAsset(importAsset)

    Files.exists(importAsset.path) should be(true)
    stagedFiles shouldBe empty

    val stored = testApp.service.fileStore.assetFile(imported.persistedId)
    Files.size(stored) should equal(imported.sizeBytes)
    Files.readAllBytes(stored) should equal(importAsset.bytes)
  }

  test("An imported clip is a Video with its duration, its display size and a Preview") {

    /**
     * Setup:
     *
     * A synthesized two-second MP4 (TestVideos.portrait): a 640x480 landscape encoding whose container says to display it as
     * portrait, the way a phone held upright records.
     *
     * Assertions:
     *
     * The clip imports as a video with its duration, its display size, a preview and a stored file of the recorded size, and
     * leaves nothing staged.
     *
     * Edge cases:
     *
     * The display rotation swaps the stored width and height relative to the encoded frame.
     */
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    imported.assetType.mediaType shouldBe "video"
    imported.assetType.mime should startWith("video/")

    val asset: Asset = testApp.service.asset.getById(imported.persistedId)
    asset.durationMs.value shouldBe 2000L +- 100
    // The container says to show the landscape encoding as portrait
    asset.width shouldBe TestVideos.HEIGHT
    asset.height shouldBe TestVideos.WIDTH

    testApp.service.asset.getPreview(asset.persistedId).data.length should be > 0
    Files.size(testApp.service.fileStore.assetFile(asset.persistedId)) shouldBe asset.sizeBytes
    stagedFiles shouldBe empty
  }

  test("A clip's Date Taken is its container's creation time, as the UTC wall clock") {

    /**
     * Setup:
     *
     * A synthesized clip (TestVideos.dated) whose container records its creation time as the UTC instant 2023-06-09 12:34:56.
     *
     * Assertions:
     *
     * The capture time is that instant as a zoneless wall-clock time, sourced from the container creation time, on the imported
     * asset and when read back.
     */
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.dated.toFile))
    imported.originalCreatedAt shouldBe Some(LocalDateTime.of(2023, 6, 9, 12, 34, 56))
    imported.originalCreatedAtSource shouldBe Some(CaptureDateSource.ContainerCreationTime)
    testApp.service.asset.getById(imported.persistedId).originalCreatedAt shouldBe imported.originalCreatedAt
  }

  test("An audio file is not a supported media type, and leaves nothing staged") {

    /**
     * Setup:
     *
     * An MP3 file (audio/all.mp3) offered for import.
     *
     * Assertions:
     *
     * The import fails as an unsupported media type, and its staged copy is discarded.
     */
    intercept[UnsupportedMediaTypeException] {
      testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("audio/all.mp3"))
    }
    stagedFiles shouldBe empty
  }

  test("Clearing staging empties it") {

    /**
     * Setup:
     *
     * Three bytes staged directly, without an import.
     *
     * Assertions:
     *
     * Staging holds the one file until it is cleared, and nothing after.
     */
    testApp.service.staging.stage(Array[Byte](1, 2, 3))
    stagedFiles should have size 1

    testApp.service.staging.clear()
    stagedFiles shouldBe empty
  }

  private def stagedFiles: List[Path] =
    val dir = testApp.service.staging.dir
    if Files.exists(dir) then Files.list(dir).toList.asScala.toList else Nil

  test("Imported image with extracted metadata should successfully import") {

    /**
     * Setup:
     *
     * A single JPEG carrying full camera EXIF (people/bullock.jpg), run through the whole import pipeline.
     *
     * Assertions:
     *
     * The JPEG is detected as an image and keeps its type, checksum and size when read back from the repository; the checksum is
     * the SHA-256 of the file in lowercase hex, what `sha256sum` prints for it. Metadata extraction ran: the raw extracted
     * metadata has the JPEG directory, and every camera field of the public metadata derived from the EXIF directories is filled
     * in - device model, exposure settings and the original capture time.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val fileSha256 = IntegrationTestUtil.jdkSha256Hex(Files.readAllBytes(importAsset.path))

    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    importedAsset.assetType.mediaType shouldBe "image"
    importedAsset.checksum shouldBe fileSha256

    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.checksum shouldBe fileSha256
    asset.sizeBytes should not be 0

    asset.extractedMetadata.getFieldValues("JPEG").get("Image Height") should not be empty

    asset.publicMetadata.deviceModel should not be empty
    asset.publicMetadata.fNumber should not be empty
    asset.publicMetadata.focalLength should not be empty
    asset.publicMetadata.iso should not be empty
    asset.publicMetadata.exposureTime should not be empty
    asset.publicMetadata.dateTimeOriginal should not be empty
  }

  test("Imported image should have a preview") {

    /**
     * Setup:
     *
     * One small JPEG (images/1.jpg) imported.
     *
     * Assertions:
     *
     * The stored preview is a WebP (a RIFF container of the WEBP form) that decodes as an image filling the square preview box.
     * The image is square, so nothing of the box is transparent and the WebP carries no alpha plane: three channels.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    val preview: MimedPreviewData = testApp.service.asset.getPreview(asset.persistedId)

    new String(preview.data.take(4), StandardCharsets.US_ASCII) shouldBe "RIFF"
    new String(preview.data.slice(8, 12), StandardCharsets.US_ASCII) shouldBe "WEBP"
    val image = IntegrationTestUtil.decodePreview(preview.data)
    val box = Const.AssetView.PREVIEW_BOX_PIXELS
    (image.cols, image.rows, image.channels) shouldBe (box, box, 3)
  }

  test("A portrait image's preview is centered in the box on a transparent border") {

    /**
     * Setup:
     *
     * A 640 by 724 JPEG (images/2.jpg) imported; scaled to fit the 200-pixel box it is 177 wide, leaving 23 columns of border.
     *
     * Assertions:
     *
     * The preview has an alpha channel. The image fills the box's height and is centered horizontally with the odd column of the
     * border after it: columns 0 to 10 and 188 to 199 are transparent, columns 11 to 187 opaque.
     */
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/2.jpg"))
    val image = IntegrationTestUtil.decodePreview(testApp.service.asset.getPreview(imported.persistedId).data)
    val box = Const.AssetView.PREVIEW_BOX_PIXELS
    (image.cols, image.rows, image.channels) shouldBe (box, box, 4)

    def alpha(row: Int, col: Int): Double = image.get(row, col)(3)
    (alpha(100, 0), alpha(100, 10), alpha(100, 11)) shouldBe (0.0, 0.0, 255.0)
    (alpha(100, 187), alpha(100, 188), alpha(100, 199)) shouldBe (255.0, 0.0, 0.0)
    (alpha(0, 100), alpha(199, 100)) shouldBe (255.0, 255.0)
  }

  test("Imported image is triaged") {

    /**
     * Setup:
     *
     * One JPEG (images/1.jpg) imported.
     *
     * Assertions:
     *
     * A fresh import lands in triage.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.isTriaged should be(true)
  }

  test("Imported asset with metadata has media creation date set") {

    /**
     * Setup:
     *
     * A JPEG with EXIF metadata (images/cactus.jpg) imported.
     *
     * Assertions:
     *
     * The stored asset has a capture time.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.originalCreatedAt should not be None
  }

  test("Imported asset without metadata has no capture date") {

    /**
     * Setup:
     *
     * A JPEG with no EXIF block (images/1.jpg) imported.
     *
     * Assertions:
     *
     * No capture time is made up for it: the stored asset has none.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.originalCreatedAt should be(None)
  }

  test("Imported image asset has width and height") {

    /**
     * Setup:
     *
     * One JPEG (images/cactus.jpg) imported.
     *
     * Assertions:
     *
     * The stored asset has its pixel dimensions.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.width should be > 0
    asset.height should be > 0
  }

  test("A still image is imported with its Image quality and perceptual hash, and a Video with neither") {

    /**
     * Setup:
     *
     * A photo with a face (people/bullock.jpg) and a synthesized two-second MP4 (TestVideos.portrait), each run through the whole
     * import.
     *
     * Assertions:
     *
     * The photo is read back with the five metrics, a score within 0 to 100, the exposure fractions within 0 to 1 (its dark
     * background crushes more than it clips, so the two columns are not swapped) and a hash, the hash the import returned,
     * written with the completion flag. The Video, which the measurement stage passes through untouched, has neither. (An
     * animated GIF is covered in `ImportPipelineServiceTests`, where the GIF fixtures are synthesized.)
     */
    val photo = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/bullock.jpg"))
    val stored: Asset = testApp.service.asset.getById(photo.persistedId)
    stored.isPipelineProcessed shouldBe true
    val quality = stored.imageQuality.value
    quality.score should ((be >= 0).and(be <= 100))
    quality.sharpness should be > 0.0
    quality.luminance should ((be > 0.0).and(be < 1.0))
    quality.clippedHighlights should ((be >= 0.0).and(be <= 1.0))
    quality.crushedShadows should ((be >= 0.0).and(be <= 1.0))
    // A dark photo: its crushed shadows outweigh its clipped highlights, which a swap of the two columns would reverse
    quality.crushedShadows should be > quality.clippedHighlights
    stored.perceptualHash.value shouldBe photo.perceptualHash.value

    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    val storedVideo: Asset = testApp.service.asset.getById(video.persistedId)
    storedVideo.imageQuality shouldBe None
    storedVideo.perceptualHash shouldBe None
  }

  test("A still image is imported with its Histogram, which its own read alone carries, and a Video with none") {

    /**
     * Setup:
     *
     * A photo (people/bullock.jpg) and a synthesized two-second MP4 (TestVideos.portrait), each run through the whole import, and
     * the photo's stored file decoded and measured again.
     *
     * Assertions:
     *
     * The Histograms read for the two hold the photo's alone, bin for bin the one measured from its file: it was written with the
     * completion, float32-exact, and the Video has none. A read by ID (the typed row) and the hand-written locking read of a
     * library operation each hand the photo back without it, a bulky column no general read selects. No IDs read nothing.
     */
    val photo = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/bullock.jpg"))
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))

    val decoded = ImageUtil.matFromBytes(Files.readAllBytes(testApp.service.fileStore.assetFile(photo.persistedId)))
    val measured: Histogram =
      try ImageMeasurement.measure(decoded, Nil).histogram
      finally decoded.release()

    val histograms = testApp.txManager.asReadOnly {
      testApp.DAO.asset.getHistograms(Set(photo.persistedId, video.persistedId))
    }
    histograms shouldBe Map(photo.persistedId -> measured)

    testApp.service.asset.getById(photo.persistedId).histogram shouldBe None
    val locked = testApp.txManager.withTransaction(testApp.DAO.asset.getAssetsToMove(Set(photo.persistedId)))
    locked.map(_.histogram) shouldBe List(None)

    testApp.txManager.asReadOnly(testApp.DAO.asset.getHistograms(Set.empty)) shouldBe empty
  }

  test("A still image is imported with the Scene embedding of each model, stored with its completion") {

    /**
     * Setup:
     *
     * Two frames of the synthesized burst a second apart (BurstFrames), each run through the whole import.
     *
     * Assertions:
     *
     * The first frame comes out of the import with an embedding of each Scene model, of the model's dimensions and unit norm. The
     * second frame's Sequence candidates, the one read that selects the two columns, hold the first with both equal to what the
     * import returned, value for value: the embeddings were written with the completion.
     */
    val first = importFrame(frame(0))
    val second = importFrame(frame(1), taken = Some(takenAt(1)))

    SceneModel.values.foreach {
      model =>
        val embedding = first.sceneEmbeddings.of(model).value
        embedding.values.length shouldBe model.dimensions
        embedding.similarity(embedding) shouldBe 1.0 +- 1e-4
    }

    // A write transaction: the candidates are locked until it ends
    val candidates = testApp.txManager.withTransaction(testApp.DAO.asset.getSequenceCandidates(second, gapMs = 3000))
    val candidate = candidates.find(_.id == first.id).value
    candidate.sceneEmbeddings shouldBe first.sceneEmbeddings
  }

  test("A still image's Scene embeddings are stored with its completion and read back float32-exact") {

    /**
     * Setup:
     *
     * Two frames of the synthesized burst a second apart (BurstFrames). The first, read back by ID with its metrics and hash, is
     * completed again through `markAsCompleted` with a synthetic unit vector of each Scene model's dimensions, of values chosen
     * rather than measured, so the read-back is checked against known numbers.
     *
     * Assertions:
     *
     * The second frame's Sequence candidates, the one read that selects the two columns, hold the first with both vectors equal
     * to what was written, value for value, and its metrics and hash still on it.
     */
    val first = importFrame(frame(0))
    val second = importFrame(frame(1), taken = Some(takenAt(1)))
    val dinov2 = unitVector(SceneModel.Dinov2, seed = 1)
    val clip = unitVector(SceneModel.Clip, seed = 2)

    val stored: Asset = testApp.service.asset.getById(first.persistedId)
    testApp.service.asset.markAsCompleted(
      stored.copy(sceneEmbeddings = SceneEmbeddings(dinov2 = Some(dinov2), clip = Some(clip))))

    // A write transaction: the candidates are locked until it ends
    val candidates = testApp.txManager.withTransaction(testApp.DAO.asset.getSequenceCandidates(second, gapMs = 3000))
    val candidate = candidates.find(_.id == first.id).value
    candidate.sceneEmbeddings shouldBe SceneEmbeddings(dinov2 = Some(dinov2), clip = Some(clip))
    candidate.imageQuality shouldBe stored.imageQuality
    candidate.perceptualHash shouldBe stored.perceptualHash
  }

  test("A general read of an asset carries no Scene embeddings, and an embedding of the wrong size is refused") {

    /**
     * Setup:
     *
     * A frame of the synthesized burst, completed again with a synthetic unit vector of each Scene model.
     *
     * Assertions:
     *
     * The columns hold the vectors, but a read by ID (the typed row) and the hand-written locking read of a library operation
     * (its own column list) each hand the asset back with neither: the embeddings are bulky columns no general read selects.
     * Completing it again with a CLIP vector of DINOv2's dimensions is refused on both engines, and the stored vectors stand.
     */
    val frameAsset = importFrame(frame(0))
    val stored: Asset = testApp.service.asset.getById(frameAsset.persistedId)
    testApp.service.asset.markAsCompleted(
      stored.copy(
        sceneEmbeddings = SceneEmbeddings(
          dinov2 = Some(unitVector(SceneModel.Dinov2, seed = 3)),
          clip = Some(unitVector(SceneModel.Clip, seed = 4)))))

    val (dinov2Column, clipColumn) = rawEmbeddingColumns(frameAsset.persistedId)
    dinov2Column should not be None
    clipColumn should not be None

    testApp.service.asset.getById(frameAsset.persistedId).sceneEmbeddings shouldBe SceneEmbeddings()
    val locked = testApp.txManager.withTransaction(testApp.DAO.asset.getAssetsToMove(Set(frameAsset.persistedId)))
    locked.map(_.sceneEmbeddings) shouldBe List(SceneEmbeddings())

    intercept[IllegalArgumentException] {
      testApp.service.asset.markAsCompleted(
        stored.copy(sceneEmbeddings = SceneEmbeddings(clip = Some(unitVector(SceneModel.Dinov2, seed = 5)))))
    }
    rawEmbeddingColumns(frameAsset.persistedId) shouldBe (dinov2Column, clipColumn)
  }

  test("A Video completes with no Scene embeddings") {

    /**
     * Setup:
     *
     * A synthesized two-second MP4 (TestVideos.portrait) run through the whole import, which completes it.
     *
     * Assertions:
     *
     * Both embedding columns of its row are NULL, and the Video reads back with neither.
     */
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    testApp.service.asset.getById(video.persistedId).isPipelineProcessed shouldBe true

    rawEmbeddingColumns(video.persistedId) shouldBe (None, None)
    testApp.service.asset.getById(video.persistedId).sceneEmbeddings shouldBe SceneEmbeddings()
  }

  test("A still image's Faces carry their sharpness and its own sharpness is the whole frame's") {

    /**
     * Setup:
     *
     * A portrait imported through the pipeline, its stored file decoded and measured again with no faces and with its stored
     * Faces, and a Video imported the same way.
     *
     * Assertions:
     *
     * The measurement ran before recognition stored the Faces, so each Face of the photo has its sharpness. The asset's sharpness
     * is the one measured with no faces, while its score is the one measured with them. The Faces of the Video have none.
     */
    val photo = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/bullock.jpg"))
    val stored: Asset = testApp.service.asset.getById(photo.persistedId)
    val faces = testApp.service.person.getAssetFaces(photo.persistedId)
    faces should not be empty
    all(faces.map(_.sharpness)) should not be None

    val decoded = ImageUtil.matFromBytes(Files.readAllBytes(testApp.service.fileStore.assetFile(photo.persistedId)))
    try
      stored.imageQuality.value.sharpness shouldBe ImageMeasurement.measure(decoded, Nil).quality.sharpness
      stored.imageQuality.value.score shouldBe ImageMeasurement.measure(decoded, faces).quality.score
    finally decoded.release()

    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.oneFace.toFile))
    val videoFaces = testApp.service.person.getAssetFaces(video.persistedId)
    videoFaces should not be empty
    all(videoFaces.map(_.sharpness)) shouldBe None
  }

  test("An image the measurement cannot decode imports unmeasured") {

    /**
     * Setup:
     *
     * Bytes no image decoder takes, staged under a JPEG asset, handed to the measurement as the pipeline would hand a staged
     * file.
     *
     * Assertions:
     *
     * The measurement does not throw: the asset comes back as it was, with no Image quality and no hash, so the import goes on
     * without the metrics.
     */
    val undecodable = AssetWithData(
      testContext.makeAsset().copy(assetType = AssetType("image", "jpeg", "image/jpeg")),
      testApp.service.staging.stage("not an image".getBytes))

    val measured = testApp.service.asset.measureImage(undecodable)
    measured shouldBe undecodable
    measured.asset.imageQuality shouldBe None
    measured.asset.perceptualHash shouldBe None
  }
}
