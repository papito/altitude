package software.altitude.test.core.controller

import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.core.models.Asset
import software.altitude.core.models.Preview
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.ControllerTestCore

@DoNotDiscover class SecuredStaticFileControllerTests(override val testApp: Altitude) extends ControllerTestCore {

  /**
   * This is failing because the server thinks the user is logged in.
   * Does not fail in this suite but does in the full test suite.
   *
   * There is weird session state that is bleeding through from other tests.
   */
  /*  test("Get denied a preview file because not logged in") {
    // no need to persist the repository or import an asset - should never get to that point
    get(s"/content/preview/lol") {
      status should equal(302)
    }
  }
*/
  test("Get preview file") {
    testContext.persistRepository() // and user

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    get(s"/content/preview/${importedAsset.persistedId}", headers=testAuthHeaders()) {
      status should equal(200)
      response.getContentType() should be(s"${Preview.MIME_TYPE};charset=utf-8")
    }
  }

  test("Get file") {
    testContext.persistRepository() // and user

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    get(s"/content/file/${importedAsset.persistedId}", headers=testAuthHeaders()) {
      status should equal(200)
    }
  }
}
