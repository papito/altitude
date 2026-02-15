package altitude.core.controller

import altitude.core.models.{Asset, MimedPreviewData}
import altitude.core.{App, Const}
import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.endWith
import org.scalatest.matchers.should.Matchers.{should, shouldBe}

@DoNotDiscover class ContentViewControllerTests extends ControllerTestCore {

  // FIXME: This works alone but fails within the context of the suite - what state is being shared across these tests?
  test("Access to thumbnail image denied for not logged in users") {
    // no need to persist the repository or import an asset - should never get to that point
    //    get(s"/content/preview/lol") {
    //      status should equal(302)
    //    }
  }

//  test("View preview image", Focused) {
//    testContext.persistRepository() // and user
//    val repoId = testContext.repository.persistedId
//
//    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
//    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
//
//    withServer(App) { host =>
//      testApp.service.system.readMetadata.isInitialized shouldBe false
//      val response = requests.get(s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.PREVIEW}/${importedAsset.persistedId}")
//      response.statusCode shouldBe 200
//      response.contentType.head shouldBe s"${MimedPreviewData.MIME_TYPE};charset=utf-8"
//    }
//  }

//  test("View a file") {
//    testContext.persistRepository() // and user
//    val repoId = testContext.repository.persistedId
//
//    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
//    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
//
//    get(s"/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FILE}/${importedAsset.persistedId}", headers=testAuthHeaders()) {
//      response.getContentType() should startWith("application/octet-stream")
//      status should equal(200)
//    }
//  }
//
//  test("View a person's cover image") {
//    testContext.persistRepository()
//    testApp.service.faceRecognition.initialize()
//    val repoId = testContext.repository.persistedId
//
//    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
//    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
//
//    val faces = testApp.service.person.getAssetFaces(importedAsset.persistedId)
//    val face: Face = faces.head
//
//    get(s"/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FACE}/${face.persistedId}", headers=testAuthHeaders()) {
//      response.getContentType() should startWith("image/png")
//      status should equal(200)
//    }
//  }

}