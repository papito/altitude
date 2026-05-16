package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.should

import altitude.core.Altitude
import altitude.core.NotFoundException

@DoNotDiscover class FileStoreServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Imported asset has binary preview and data stored on the file system") {
    val asset = testContext.persistAsset()

    val assetPreview = testApp.service.fileStore.getPreviewById(asset.persistedId)
    assetPreview.data.length should be > 0

    val assetData = testApp.service.fileStore.getAssetById(asset.persistedId)
    assetData.data.length should be > 0
  }

  test("Purging an asset removes preview and file from file store") {
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
