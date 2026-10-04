package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.imageio.ImageIO
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.MimedPreviewData

@DoNotDiscover class AssetImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

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
     * The JPEG is detected as an image and keeps its type, checksum and size when read back from the repository. Metadata
     * extraction ran: the raw extracted metadata has the JPEG directory, and every camera field of the public metadata derived
     * from the EXIF directories is filled in - device model, exposure settings and the original capture time.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")

    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    importedAsset.assetType.mediaType shouldBe "image"
    importedAsset.checksum should not be 0

    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.checksum should not be 0
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
     * The stored preview decodes as an image filling the square preview box.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    val preview: MimedPreviewData = testApp.service.asset.getPreview(asset.persistedId)

    // ImageIO returns null for bytes no reader recognizes
    val image = Option(ImageIO.read(new ByteArrayInputStream(preview.data))).value
    image.getWidth shouldBe Const.AssetView.PREVIEW_BOX_PIXELS
    image.getHeight shouldBe Const.AssetView.PREVIEW_BOX_PIXELS
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
}
