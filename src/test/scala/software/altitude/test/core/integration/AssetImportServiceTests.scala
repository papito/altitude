package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.models.Asset
import software.altitude.core.models.Preview
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class AssetImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Import duplicate") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/2.jpg")
    testApp.service.assetImport.importAsset(importAsset).get

    intercept[DuplicateException] {
      testApp.service.assetImport.importAsset(importAsset).get
    }
  }

  test("Imported image WITHOUT metadata should successfully import") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.checksum should not be empty

    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.checksum should not be empty
    asset.sizeBytes should not be 0
  }

  test("Imported image WITH metadata should should successfully import") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.checksum should not be empty

    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.checksum should not be empty
    asset.sizeBytes should not be 0
  }

  test("Imported image should have a preview") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get
    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    val preview: Preview = testApp.service.library.getPreview(asset.persistedId)

    preview.mimeType should equal("application/octet-stream")
    preview.data.length should not be 0
  }

  test("Imported image is triaged") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get
    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    asset.isTriaged should be(true)
  }

}
