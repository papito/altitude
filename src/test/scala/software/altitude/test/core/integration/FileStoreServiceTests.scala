package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import software.altitude.core.models.Asset
import software.altitude.test.Util.getImportAsset
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class FileStoreServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Add asset") {
    val importAsset = getImportAsset(("images/1.jpg"))
    val importedAsset: Asset = altitude.service.assetImport.importAsset(importAsset).get
    val asset = altitude.service.library.getById(importedAsset.persistedId): Asset
  }
}
