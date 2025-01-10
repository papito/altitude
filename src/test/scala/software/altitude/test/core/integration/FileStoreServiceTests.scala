package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.core.models.Asset
import software.altitude.test.IntegrationTestUtil.getImportAsset
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class FileStoreServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Add asset") {
    val importAsset = getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val asset = testApp.service.library.getById(importedAsset.persistedId): Asset
  }
}
