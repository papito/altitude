package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.DuplicateException
import software.altitude.core.models.Asset
import software.altitude.core.models.Preview
import software.altitude.test.Util
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class AssetImportServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Import duplicate") {
    val importAsset = Util.getImportAsset("images/2.jpg")
    altitude.service.assetImport.importAsset(importAsset).get

    intercept[DuplicateException] {
      altitude.service.assetImport.importAsset(importAsset).get
    }
  }

  test("Imported image should have all properties set") {
    val importAsset = Util.getImportAsset("images/1.jpg")
    val importedAsset: Asset = altitude.service.assetImport.importAsset(importAsset).get

    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.checksum should not be empty

    val asset = altitude.service.library.getById(importedAsset.persistedId): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.checksum should not be empty
    asset.sizeBytes should not be 0
  }

  test("Imported image should have a preview") {
    val importAsset = Util.getImportAsset("images/1.jpg")
    val importedAsset: Asset = altitude.service.assetImport.importAsset(importAsset).get
    val asset = altitude.service.library.getById(importedAsset.persistedId): Asset
    val preview: Preview = altitude.service.library.getPreview(asset.persistedId)

    preview.mimeType should equal("application/octet-stream")
    preview.data.length should not be 0
  }

  test("Imported image is triaged") {
    val importAsset = Util.getImportAsset("images/1.jpg")
    val importedAsset: Asset = altitude.service.assetImport.importAsset(importAsset).get
    val asset = altitude.service.library.getById(importedAsset.persistedId): Asset
    asset.isTriaged should be(true)
  }

}
