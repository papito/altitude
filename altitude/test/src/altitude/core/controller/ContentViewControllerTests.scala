package altitude.core.controller

import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.{ endWith, startWith }
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }

import altitude.core.{ App, Const }
import altitude.core.models.{ Asset, Face }

@DoNotDiscover class ContentViewControllerTests extends ControllerTestCore {

  test("View preview image") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported.
     *
     * Assertions:
     *
     * The preview content route serves the asset's preview as a WebP.
     */
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
        response.contentType.head shouldBe "image/webp"
    }
  }

  test("A preview and a face are cacheable for an hour, privately; the stored original is not") {

    /**
     * Setup:
     *
     * A logged-in user's repository with people/meme-ben.jpg imported, in which the import detects a face.
     *
     * Assertions:
     *
     * The preview and the face content routes answer with a private, hour-long Cache-Control, and the file content route answers
     * with none.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val face: Face = testApp.service.person.getAssetFaces(importedAsset.persistedId).head

    login()

    withServer(App) {
      host =>
        def cacheControl(url: String): Option[Seq[String]] =
          requests.get(url, cookies = testContext.cookies).headers.get("cache-control")

        val privateHour = Some(Seq("private, max-age=3600"))
        cacheControl(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.PREVIEW}/${importedAsset.persistedId}") shouldBe privateHour
        cacheControl(
          s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.FACE}/${face.persistedId}") shouldBe privateHour
        cacheControl(fileUrl(host, repoId, importedAsset)) shouldBe None
    }
  }

  test("A preview is never gzipped, however the client names gzip among the codings it accepts") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported, its preview requested with different Accept-Encoding headers.
     *
     * Assertions:
     *
     * The preview is sent uncompressed whether or not the client accepts gzip: an image is deflated already.
     *
     * Edge cases:
     *
     * gzip alone, in a list with and without a space after the comma, with a quality weight, in upper case, and identity.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importAsset = IntegrationTestUtil.getImportAsset("images/1.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    login()

    withServer(App) {
      host =>
        Seq("gzip", "gzip,deflate", "deflate, gzip", "gzip;q=1.0, deflate", "GZIP", "identity").foreach {
          acceptEncoding =>
            withClue(acceptEncoding) {
              val response = requests.get(
                s"$host/${Const.DataStore.CONTENT}/r/$repoId/${Const.DataStore.PREVIEW}/${importedAsset.persistedId}",
                headers = Map("Accept-Encoding" -> acceptEncoding),
                cookies = testContext.cookies
              )
              response.headers.contains("content-encoding") shouldBe false
            }
        }
    }
  }

  test("The search results fragment is gzipped for a client that accepts gzip, and plain for one that does not") {

    /**
     * Setup:
     *
     * A logged-in user's repository, its search results fragment requested with gzip and identity Accept-Encoding headers.
     *
     * Assertions:
     *
     * The HTML fragment is gzipped for a client that accepts gzip, and sent uncompressed for one that accepts identity only.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    login()

    withServer(App) {
      host =>
        def searchResults(acceptEncoding: String) = requests.get(
          s"$host/htmx/search/r/$repoId",
          headers = Map("Accept-Encoding" -> acceptEncoding),
          cookies = testContext.cookies
        )

        val gzipped = searchResults("gzip")
        gzipped.statusCode shouldBe 200
        gzipped.contentType.head should startWith("text/html")
        gzipped.headers("content-encoding") shouldBe Seq("gzip")
        searchResults("identity").headers.contains("content-encoding") shouldBe false
    }
  }

  test("View a file: the whole original, typed as detected, offering byte ranges") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported.
     *
     * Assertions:
     *
     * The file content route sends the stored original byte for byte, with its full length, typed as the JPEG it was detected as,
     * and offers byte ranges.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported, its file requested with Range headers by a client that also
     * accepts gzip.
     *
     * Assertions:
     *
     * A satisfiable range is a 206 carrying exactly that window of the original, with a matching Content-Range and length and no
     * compression. A range that starts at or past the end is a 416 naming the file's size.
     *
     * Edge cases:
     *
     * An open-ended range, a suffix range of the last bytes, a start exactly at the file's size, and positions too long for a
     * Long - a start that is past the end, and a suffix that covers the whole file.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository with people/meme-ben.jpg imported, in which the import detects a face.
     *
     * Assertions:
     *
     * The face content route, which person covers are drawn from, serves that face's display image as a PNG.
     */
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
