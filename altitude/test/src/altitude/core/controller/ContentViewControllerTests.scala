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

  test("A preview is gzipped for a client that names gzip among other codings, spaced or weighted or not") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    login()

    withServer(App) {
      host =>
        def preview(acceptEncoding: String) = requests.get(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.PREVIEW}/${importedAsset.persistedId}",
          headers = Map("Accept-Encoding" -> acceptEncoding),
          cookies = testContext.cookies
        )

        Seq("gzip", "gzip,deflate", "deflate, gzip", "gzip;q=1.0, deflate", "GZIP").foreach {
          acceptEncoding =>
            withClue(acceptEncoding) {
              preview(acceptEncoding).headers("content-encoding") shouldBe Seq("gzip")
            }
        }
        preview("identity").headers.contains("content-encoding") shouldBe false
    }
  }

  test("View a file: the whole original, typed as detected, offering byte ranges") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    login()

    withServer(App) {
      host =>
        val response = requests.get(fileUrl(host, repoId, importedAsset), cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head should startWith("image/jpeg")
        response.headers("accept-ranges") shouldBe Seq("bytes")
        response.headers("content-length") shouldBe Seq(importedAsset.sizeBytes.toString)
        response.bytes shouldBe importAsset.bytes
    }
  }

  test("A byte range of a file is a 206 window of it, uncompressed, and one past the end is a 416") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val size = importedAsset.sizeBytes

    login()

    withServer(App) {
      host =>
        def range(value: String) = requests.get(
          fileUrl(host, repoId, importedAsset),
          headers = Map("Range" -> value, "Accept-Encoding" -> "gzip"),
          cookies = testContext.cookies,
          check = false)

        val first = range("bytes=0-99")
        first.statusCode shouldBe 206
        first.headers("content-range") shouldBe Seq(s"bytes 0-99/$size")
        first.headers("content-length") shouldBe Seq("100")
        first.headers.contains("content-encoding") shouldBe false
        first.bytes shouldBe importAsset.bytes.take(100)

        val tail = range(s"bytes=${size - 10}-")
        tail.statusCode shouldBe 206
        tail.headers("content-range") shouldBe Seq(s"bytes ${size - 10}-${size - 1}/$size")
        tail.bytes shouldBe importAsset.bytes.takeRight(10)

        val last = range("bytes=-10")
        last.statusCode shouldBe 206
        last.bytes shouldBe importAsset.bytes.takeRight(10)

        val pastTheEnd = range(s"bytes=$size-")
        pastTheEnd.statusCode shouldBe 416
        pastTheEnd.headers("content-range") shouldBe Seq(s"bytes */$size")

        // A position too long for a Long is past the end too, not a server error
        range("bytes=99999999999999999999-").statusCode shouldBe 416
        val lastTooMany = range("bytes=-99999999999999999999")
        lastTooMany.statusCode shouldBe 206
        lastTooMany.bytes shouldBe importAsset.bytes
    }
  }

  private def fileUrl(host: String, repoId: String, asset: Asset): String =
    s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FILE}/${asset.persistedId}"

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
