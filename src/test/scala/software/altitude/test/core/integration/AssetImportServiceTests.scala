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
import software.altitude.core.models.MimedPreviewData
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

  test("Imported image with extracted metadata should successfully import") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.checksum should not be 0

    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
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
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get
    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    val preview: MimedPreviewData = testApp.service.library.getPreview(asset.persistedId)

    preview.mimeType should equal(MimedPreviewData.MIME_TYPE)
    preview.data.length should not be 0
  }

  test("Imported image is triaged") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get
    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
    asset.isTriaged should be(true)
  }

}
