package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.NotFoundException
import altitude.core.models.MimedPreviewData

@DoNotDiscover class FileStoreServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Imported asset has binary preview and data stored on the file system") {

    /**
     * Setup:
     *
     * One asset over a random image, imported through the pipeline.
     *
     * Assertions:
     *
     * The file store holds a non-empty WebP preview and a non-empty file for it.
     */
    val asset = testContext.persistAsset()

    val assetPreview = testApp.service.fileStore.getPreviewById(asset.persistedId)
    assetPreview.data.length should be > 0
    assetPreview.mimeType shouldBe MimedPreviewData.MIME_TYPE

    val assetData = testApp.service.fileStore.getAssetById(asset.persistedId)
    assetData.data.length should be > 0
  }

  test("Purging an asset removes preview and file from file store") {

    /**
     * Setup:
     *
     * One imported asset whose files are then purged from the file store directly.
     *
     * Assertions:
     *
     * Its preview and its file are both gone.
     */
    val asset = testContext.persistAsset()

    testApp.service.fileStore.purgeAssetById(asset.persistedId)

    intercept[NotFoundException] {
      testApp.service.fileStore.getPreviewById(asset.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.fileStore.getAssetById(asset.persistedId)
    }
  }
}
