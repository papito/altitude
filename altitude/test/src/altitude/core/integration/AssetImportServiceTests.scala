package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.MimedPreviewData

@DoNotDiscover class AssetImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Import duplicate") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/2.jpg")
    testApp.service.library.addImportAsset(importAsset)

    intercept[DuplicateException] {
      testApp.service.library.addImportAsset(importAsset)
    }

    // The duplicate's staged copy is discarded
    stagedFiles shouldBe empty
  }

  test("An import stages a copy of the file, then renames it into the store under the asset's ID") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/2.jpg")
    val imported: Asset = testApp.service.library.addImportAsset(importAsset)

    Files.exists(importAsset.path) should be(true)
    stagedFiles shouldBe empty

    val stored = testApp.service.fileStore.assetFile(imported.persistedId)
    Files.size(stored) should equal(imported.sizeBytes)
    Files.readAllBytes(stored) should equal(importAsset.bytes)
  }

  test("An imported clip is a Video with its duration, its display size and a Preview") {
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
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.dated.toFile))
    imported.originalCreatedAt shouldBe Some(LocalDateTime.of(2023, 6, 9, 12, 34, 56))
    imported.originalCreatedAtSource shouldBe Some(CaptureDateSource.ContainerCreationTime)
    testApp.service.asset.getById(imported.persistedId).originalCreatedAt shouldBe imported.originalCreatedAt
  }

  test("An audio file is not a supported media type, and leaves nothing staged") {
    intercept[UnsupportedMediaTypeException] {
      testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("audio/all.mp3"))
    }
    stagedFiles shouldBe empty
  }

  test("Clearing staging empties it") {
    testApp.service.staging.stage(Array[Byte](1, 2, 3))
    stagedFiles should have size 1

    testApp.service.staging.clear()
    stagedFiles shouldBe empty
  }

  private def stagedFiles: List[Path] =
    val dir = testApp.service.staging.dir
    if Files.exists(dir) then Files.list(dir).toList.asScala.toList else Nil

  test("Imported image with extracted metadata should successfully import") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")

    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    importedAsset.assetType should equal(importedAsset.assetType)
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
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    val preview: MimedPreviewData = testApp.service.asset.getPreview(asset.persistedId)

    preview.mimeType should equal(MimedPreviewData.MIME_TYPE)
    preview.data.length should not be 0
  }

  test("Imported image is triaged") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.isTriaged should be(true)
  }

  test("Imported asset with metadata has media creation date set") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.originalCreatedAt should not be None
  }

  test("Imported asset without metadata has no capture date") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.originalCreatedAt should be(None)
  }

  test("Imported image asset has width and height") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset
    asset.width should be > 0
    asset.height should be > 0
  }
}
