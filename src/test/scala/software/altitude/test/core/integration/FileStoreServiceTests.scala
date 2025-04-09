package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.{Altitude, NotFoundException}
import software.altitude.core.models.Asset
import software.altitude.test.IntegrationTestUtil.getImportAsset
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class FileStoreServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Imported asset has binary preview and data stored on the file system") {
    val importAsset = getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset

    val assetPreview = testApp.service.fileStore.getPreviewById(asset.persistedId)
    assetPreview.data.length should be > 0

    val assetData = testApp.service.fileStore.getAssetById(asset.persistedId)
    assetData.data.length should be > 0
  }

  test("Purging an asset removes preview and file from file store") {
    val importAsset = getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.asset.getById(importedAsset.persistedId): Asset

    testApp.service.fileStore.purgeAssetById(asset.persistedId)

    intercept[NotFoundException] {
      testApp.service.fileStore.getPreviewById(asset.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.fileStore.getAssetById(asset.persistedId)
    }
  }
}
