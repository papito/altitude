package altitude.core.controller

import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.{ endWith, startWith }
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }

import altitude.core.{ App, Const }
import altitude.core.models.{ Asset, Face, MimedPreviewData }

@DoNotDiscover class ContentViewControllerTests extends ControllerTestCore {

  test("View preview image") {
    testContext.persistRepository() // and user
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    login()

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.PREVIEW}/${importedAsset.persistedId}",
          cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head shouldBe s"${MimedPreviewData.MIME_TYPE}"
    }
  }

  test("View a file") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    login()

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FILE}/${importedAsset.persistedId}",
          cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head should startWith("application/octet-stream")
    }
  }

  test("View a person's cover image") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val faces = testApp.service.person.getAssetFaces(importedAsset.persistedId)
    val face: Face = faces.head

    login()

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FACE}/${face.persistedId}",
          cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head should startWith("image/png")
    }
  }
}
