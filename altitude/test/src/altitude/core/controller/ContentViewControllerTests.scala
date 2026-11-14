package altitude.core.controller

import altitude.test.IntegrationTestUtil
import ch.qos.logback.classic.{ Level, Logger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Instant
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.startWith
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.{ App, Const }
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.{ AccountType, Asset, EditRecipe, Face, ImportAsset, Permissions, UserMetadata }
import altitude.core.routes.decorators

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
        val response = requests.get(previewUrl(host, repoId, importedAsset.persistedId), cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head shouldBe "image/webp"
    }
  }

  test("Content requires a login and a repository the user can see") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported, and a second user's repository with images/2.jpg imported.
     *
     * Assertions:
     *
     * A preview request without a cookie is redirected to the login page. With the cookie, the user's own preview is served, and
     * the other user's preview is a 404, as for any foreign entity.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/1.jpg"))
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository()
    testContext.grant(stranger, strangersRepo)
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/2.jpg"))

    withServer(App) {
      host =>
        val anonymous = requests.get(previewUrl(host, repoId, importedAsset.persistedId), maxRedirects = 0, check = false)
        anonymous.statusCode shouldBe 302
        anonymous.headers("location").head should startWith("/login")

        requests
          .get(previewUrl(host, repoId, importedAsset.persistedId), cookies = testContext.cookies)
          .statusCode shouldBe 200

        requests
          .get(previewUrl(host, strangersRepo.persistedId, foreign.persistedId), cookies = testContext.cookies, check = false)
          .statusCode shouldBe 404
    }
  }

  test("An original, a preview and a face of another repository of the user's are 404s, signed or not") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs (they hold a grant in both) holding an imported image with
     * a Person's Face on it, its files and the Face's images stored.
     *
     * Assertions:
     *
     * Asked for through the first repository, the image's original, its preview and the Face's image are plain-text 404s, with
     * the session's cookie and by a URL the server signed for that path alike, and none serves a byte of the other repository's.
     * Through the second repository the three are served.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    val otherRepo = testContext.persistRepository()
    testContext.grant(testContext.user, otherRepo)
    testApp.service.repository.switchContextToRepository(otherRepo)
    val foreign = testContext.persistAsset(repository = Some(otherRepo))
    val person = testApp.service.person.addPerson(altitude.core.models.Person())
    val face = testContext.addTestFace(person, foreign, altitude.test.TestContext.unitVector(1), withFiles = true)
    testApp.service.repository.switchContextToRepository(repo)

    withServer(App) {
      host =>
        val items = List(
          Const.DataStore.FILE -> foreign.persistedId,
          Const.DataStore.PREVIEW -> foreign.persistedId,
          Const.DataStore.FACE -> face.persistedId)

        for ((dataType, itemId) <- items) withClue(s"[$dataType]: ") {
          val own = requests.get(contentUrl(host, otherRepo.persistedId, dataType, itemId), cookies = testContext.cookies)
          own.statusCode shouldBe 200

          for (
            response <- List(
              requests.get(contentUrl(host, repoId, dataType, itemId), cookies = testContext.cookies, check = false),
              requests.get(signedUrl(host, repoId, dataType, itemId), check = false)
            )
          ) {
            response.statusCode shouldBe 404
            response.headers("content-type") shouldBe Seq("text/plain")
            response.text() shouldBe "Not found"
          }
        }
    }
  }

  test("An item ID that is not a UUID is a 400, and one that names nothing stored is a 404, never cached") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/2.jpg imported, its stored preview and original then deleted from disk.
     *
     * Assertions:
     *
     * An item ID that is not a UUID is a plain-text 400 for every data type. A UUID that names no asset or face, a preview or an
     * original whose file is gone, and an unknown data type are plain-text 404s. None of them carries a Cache-Control.
     *
     * Edge cases:
     *
     * A path traversal attempt as the item ID.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val gone: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/2.jpg"))
    testApp.service.fileStore.purgeAssetById(gone.persistedId)
    login()

    withServer(App) {
      host =>
        def get(url: String): requests.Response = requests.get(url, cookies = testContext.cookies, check = false)

        def plainError(response: requests.Response, status: Int): Unit =
          response.statusCode shouldBe status
          response.contentType.head should startWith("text/plain")
          response.headers.contains("cache-control") shouldBe false

        Seq(Const.DataStore.PREVIEW, Const.DataStore.FILE, Const.DataStore.FACE).foreach {
          dataType =>
            withClue(dataType) {
              Seq("not-a-uuid", "..%2F..%2Fsecret").foreach {
                itemId =>
                  val response = get(contentUrl(host, repoId, dataType, itemId))
                  plainError(response, 400)
                  response.text() shouldBe Const.Msg.Err.VALUE_NOT_A_UUID
              }
              plainError(get(contentUrl(host, repoId, dataType, BaseDao.genId)), 404)
            }
        }

        plainError(get(previewUrl(host, repoId, gone.persistedId)), 404)
        plainError(get(fileUrl(host, repoId, gone.persistedId)), 404)
        plainError(get(contentUrl(host, repoId, "unknown", BaseDao.genId)), 404)
    }
  }

  test("A preview, a face and an original are cached for good, never sniffed, and sandboxed") {

    /**
     * Setup:
     *
     * A logged-in user's repository with people/meme-ben.jpg imported, in which the import detects a face.
     *
     * Assertions:
     *
     * The preview, the face and the file content routes all answer with a private, year-long, immutable Cache-Control, with
     * nosniff, and with a sandbox Content-Security-Policy: the bytes under a content URL never change, and an original is a
     * user's upload served on the application's origin.
     *
     * Edge cases:
     *
     * A 206 window of the original, and a 416 for a range past its end.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/meme-ben.jpg"))
    val face: Face = testApp.service.person.getAssetFaces(importedAsset.persistedId).head

    login()

    withServer(App) {
      host =>
        def get(url: String, headers: Map[String, String] = Map.empty): requests.Response =
          requests.get(url, headers = headers, cookies = testContext.cookies, check = false)

        Seq(
          200 -> get(previewUrl(host, repoId, importedAsset.persistedId)),
          200 -> get(faceUrl(host, repoId, face.persistedId)),
          200 -> get(fileUrl(host, repoId, importedAsset.persistedId)),
          206 -> get(fileUrl(host, repoId, importedAsset.persistedId), Map("Range" -> "bytes=0-99")),
          416 -> get(fileUrl(host, repoId, importedAsset.persistedId), Map("Range" -> s"bytes=${importedAsset.sizeBytes}-"))
        ).foreach {
          case (status, response) =>
            withClue(response.url) {
              response.statusCode shouldBe status
              response.headers("cache-control") shouldBe Seq("private, max-age=31536000, immutable")
              response.headers("x-content-type-options") shouldBe Seq("nosniff")
              response.headers("content-security-policy") shouldBe Seq("sandbox")
            }
        }
    }
  }

  test("An original is offered inline under its own file name; a preview and a face are not") {

    /**
     * Setup:
     *
     * A logged-in user's repository with people/meme-ben.jpg imported under a file name with non-ASCII characters, spaces, a
     * double quote and a backslash, in which the import detects a face.
     *
     * Assertions:
     *
     * The whole original and a 206 window of it answer with an inline Content-Disposition carrying the file name twice: as ASCII,
     * with every other character, the quote and the backslash replaced, and as RFC 5987 UTF-8, percent-encoded. The preview and
     * the face carry no Content-Disposition.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val fixture = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importAsset = ImportAsset("""Crème brûlée "ben"\1.jpg""", fixture.path, UserMetadata())
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val face: Face = testApp.service.person.getAssetFaces(importedAsset.persistedId).head

    login()

    withServer(App) {
      host =>
        val disposition =
          """inline; filename="Cr_me br_l_e _ben__1.jpg"; filename*=UTF-8''Cr%C3%A8me%20br%C3%BBl%C3%A9e%20%22ben%22%5C1.jpg"""

        val whole = requests.get(fileUrl(host, repoId, importedAsset.persistedId), cookies = testContext.cookies)
        whole.statusCode shouldBe 200
        whole.headers("content-disposition") shouldBe Seq(disposition)

        val window =
          requests.get(
            fileUrl(host, repoId, importedAsset.persistedId),
            headers = Map("Range" -> "bytes=0-99"),
            cookies = testContext.cookies)
        window.statusCode shouldBe 206
        window.headers("content-disposition") shouldBe Seq(disposition)

        Seq(previewUrl(host, repoId, importedAsset.persistedId), faceUrl(host, repoId, face.persistedId)).foreach {
          url => requests.get(url, cookies = testContext.cookies).headers.contains("content-disposition") shouldBe false
        }
    }
  }

  test("A signed URL serves a preview, a face and an original without a login, as a logged-in request is served") {

    /**
     * Setup:
     *
     * A user's repository with people/meme-ben.jpg imported, in which the import detects a face, each item requested by a URL
     * signed by the server's signer, without a cookie, and by its plain URL with the user's cookie.
     *
     * Assertions:
     *
     * The preview, the face, the whole original and a 206 window of it are served to the signed request, with the status, the
     * bytes and the content headers (type, length, range, caching, nosniff, sandbox, disposition) of the logged-in request.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/meme-ben.jpg"))
    val face: Face = testApp.service.person.getAssetFaces(importedAsset.persistedId).head

    login()

    withServer(App) {
      host =>
        val contentHeaders = Seq(
          "content-type",
          "content-length",
          "content-range",
          "accept-ranges",
          "cache-control",
          "x-content-type-options",
          "content-security-policy",
          "content-disposition"
        )

        Seq(
          (200, Const.DataStore.PREVIEW, importedAsset.persistedId, Map.empty[String, String]),
          (200, Const.DataStore.FACE, face.persistedId, Map.empty[String, String]),
          (200, Const.DataStore.FILE, importedAsset.persistedId, Map.empty[String, String]),
          (206, Const.DataStore.FILE, importedAsset.persistedId, Map("Range" -> "bytes=0-99"))
        ).foreach {
          case (status, dataType, itemId, headers) =>
            withClue(s"$dataType $headers") {
              val signed =
                requests.get(signedUrl(host, repoId, dataType, itemId), headers = headers, maxRedirects = 0, check = false)
              val loggedIn =
                requests.get(contentUrl(host, repoId, dataType, itemId), headers = headers, cookies = testContext.cookies)

              signed.statusCode shouldBe status
              loggedIn.statusCode shouldBe status
              contentHeaders.foreach(name => withClue(name)(signed.headers.get(name) shouldBe loggedIn.headers.get(name)))
              signed.bytes shouldBe loggedIn.bytes
            }
        }
    }
  }

  test("A signed URL with a moved expiry, a changed signature or past its expiry is a 403, never cached") {

    /**
     * Setup:
     *
     * A user's repository with images/1.jpg imported, its preview requested by signed URLs without a cookie.
     *
     * Assertions:
     *
     * The signed URL with its expiry moved a second, with one character of its signature changed, and one signed with an expiry
     * already past are each a plain-text 403 with no Cache-Control, all with one body, so a refusal does not say which it was.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/1.jpg"))

    withServer(App) {
      host =>
        val path = contentPath(repoId, Const.DataStore.PREVIEW, importedAsset.persistedId)
        val exp = Instant.now.plusSeconds(3600).getEpochSecond
        val sig = signature(App.altitude.service.contentSigner.sign(path, Instant.ofEpochSecond(exp)))
        val changedSig = (if sig.head == 'A' then 'B' else 'A') +: sig.tail

        val refusals = Seq(
          s"$host$path?exp=${exp + 1}&sig=$sig",
          s"$host$path?exp=$exp&sig=$changedSig",
          signedUrl(host, repoId, Const.DataStore.PREVIEW, importedAsset.persistedId, Instant.now.minusSeconds(60))
        ).map(url => requests.get(url, maxRedirects = 0, check = false))

        refusals.foreach {
          response =>
            withClue(response.url) {
              response.statusCode shouldBe 403
              response.contentType.head should startWith("text/plain")
              response.headers.contains("cache-control") shouldBe false
            }
        }
        refusals.map(_.text()).distinct.size shouldBe 1
    }
  }

  test("A signature for one item opens neither another item nor the same item as another data type") {

    /**
     * Setup:
     *
     * A user's repository with images/1.jpg and images/2.jpg imported, the first asset's preview signed.
     *
     * Assertions:
     *
     * The signature's exp and sig on the second asset's preview path, and on the first asset's file path, are 403s without a
     * cookie: the signature covers the path, which names one item of one data type.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val signedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/1.jpg"))
    val otherAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/2.jpg"))

    withServer(App) {
      host =>
        val signed = signedUrl(host, repoId, Const.DataStore.PREVIEW, signedAsset.persistedId)
        val query = signed.substring(signed.indexOf('?'))

        Seq(
          previewUrl(host, repoId, otherAsset.persistedId),
          fileUrl(host, repoId, signedAsset.persistedId)
        ).foreach {
          url =>
            withClue(url) {
              requests.get(url + query, maxRedirects = 0, check = false).statusCode shouldBe 403
            }
        }
    }
  }

  test("A request with only one of exp and sig is not signed: the login redirect without a cookie, served with one") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported, its preview requested with only the exp, and with only the sig,
     * of a valid signed URL.
     *
     * Assertions:
     *
     * Without a cookie, each is redirected to the login page, as an unsigned request is. With the cookie, each is served: the
     * lone parameter is not an unknown argument.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/1.jpg"))

    login()

    withServer(App) {
      host =>
        val path = contentPath(repoId, Const.DataStore.PREVIEW, importedAsset.persistedId)
        val signed = App.altitude.service.contentSigner.sign(path, Instant.now.plusSeconds(3600))
        val exp = signed.substring(signed.indexOf("exp=")).takeWhile(_ != '&')

        Seq(s"$host$path?$exp", s"$host$path?sig=${signature(signed)}").foreach {
          url =>
            withClue(url) {
              val anonymous = requests.get(url, maxRedirects = 0, check = false)
              anonymous.statusCode shouldBe 302
              anonymous.headers("location").head should startWith("/login")

              requests.get(url, cookies = testContext.cookies, check = false).statusCode shouldBe 200
            }
        }
    }
  }

  test("A signed URL for a repository that does not exist is a 404") {

    /**
     * Setup:
     *
     * A preview path naming a random repository and a random item, signed by the server's signer.
     *
     * Assertions:
     *
     * The signed URL, without a cookie, is a plain-text 404, as a request for a repository that does not exist is with a login.
     */
    withServer(App) {
      host =>
        val response =
          requests.get(signedUrl(host, BaseDao.genId, Const.DataStore.PREVIEW, BaseDao.genId), maxRedirects = 0, check = false)
        response.statusCode shouldBe 404
        response.contentType.head should startWith("text/plain")
    }
  }

  test("A request with exp and sig is answered by the signature alone, whoever is logged in") {

    /**
     * Setup:
     *
     * A logged-in user's repository with images/1.jpg imported, and a second user's repository with images/2.jpg imported.
     *
     * Assertions:
     *
     * A valid signed URL for the second user's preview is served with the first user's cookie, which alone would be a 404 there.
     * The first user's own preview, with one character of the signature changed, is a 403 with their cookie, which alone would
     * serve it.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val ownAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/1.jpg"))
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository()
    testContext.grant(stranger, strangersRepo)
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/2.jpg"))

    withServer(App) {
      host =>
        def get(url: String): requests.Response =
          requests.get(url, cookies = testContext.cookies, maxRedirects = 0, check = false)

        get(signedUrl(host, strangersRepo.persistedId, Const.DataStore.PREVIEW, foreign.persistedId)).statusCode shouldBe 200

        val signed = signedUrl(host, repoId, Const.DataStore.PREVIEW, ownAsset.persistedId)
        val sig = signature(signed)
        val changedSig = (if sig.head == 'A' then 'B' else 'A') +: sig.tail
        get(signed.stripSuffix(sig) + changedSig).statusCode shouldBe 403
    }
  }

  test("A refused signature is logged with the path as requested, so an encoded line break cannot forge a log line") {

    /**
     * Setup:
     *
     * Anonymous preview requests whose repository ID carries a percent-encoded CR LF and a fake log line, one with a signature
     * that does not match and one with an expiry already past.
     *
     * Assertions:
     *
     * Both are 403s, and the decorators log each refusal with the path still percent-encoded, so nothing they log carries a line
     * break.
     */
    withServer(App) {
      host =>
        val path = s"/${Const.DataStore.CONTENT}/r/x%0D%0AFORGED/${Const.DataStore.PREVIEW}/${BaseDao.genId}"

        val logged = decoratorsLogged {
          Seq(Instant.now.plusSeconds(3600), Instant.now.minusSeconds(60)).foreach {
            expiresAt =>
              requests
                .get(s"$host$path?exp=${expiresAt.getEpochSecond}&sig=x", maxRedirects = 0, check = false)
                .statusCode shouldBe 403
          }
        }

        logged.count(message => message.startsWith("Signed URL for [") && message.contains("x%0D%0AFORGED")) shouldBe 2
        logged.exists(message => message.contains('\n') || message.contains('\r')) shouldBe false
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
                previewUrl(host, repoId, importedAsset.persistedId),
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
        val response = requests.get(fileUrl(host, repoId, importedAsset.persistedId), cookies = testContext.cookies)
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
          fileUrl(host, repoId, importedAsset.persistedId),
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

  /** An asset of the context repository imported from `images/1.jpg`, its Edit saved `saves` times, each save rendered */
  private def editedAsset(saves: Int, isTriaged: Boolean = false): Asset =
    val asset = testContext.persistAsset(isTriaged = isTriaged)
    for (version <- 1 to saves) {
      testApp.service.assetEdit.save(
        asset.persistedId,
        version - 1,
        EditRecipe.Identity.copy(exposure = version * 0.25),
        testContext.user)
      testApp.service.editRender.render(asset.persistedId)
    }
    asset

  /** The bytes of an asset's stored edited image of `kind` */
  private def editedBytes(assetId: String, kind: String): Array[Byte] =
    java.nio.file.Files.readAllBytes(testApp.service.fileStore.editedImagePath(assetId, kind))

  test("An edited Preview and a Display image are served at the asset's revision and below it, and not once it has none") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an imported image saved twice, each save rendered, so its edited images are revision
     * 2's; then its Edit reverted to the identity and rendered, so it has none.
     *
     * Assertions:
     *
     * `preview?v=2` and `display?v=2` serve the edited images as WebP, and so does `v=1`, an earlier revision, which a page no
     * longer builds: it is answered the current images. The Preview without `v` is the original's. Each carries the content
     * headers as every content answer does: cached for good, nosniff, sandboxed. Once the Edit is the identity, `preview?v=2` and
     * `display?v=2` are plain-text 404s, never cached, while the Preview without `v` is still the original's.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val assetId = editedAsset(saves = 2).persistedId
    login()

    withServer(App) {
      host =>
        def get(dataType: String, query: String): requests.Response =
          requests.get(contentUrl(host, repoId, dataType, assetId) + query, cookies = testContext.cookies, check = false)

        val original = testApp.service.fileStore.getPreviewById(assetId).data
        val preview = editedBytes(assetId, Const.DataStore.PREVIEW)
        val display = editedBytes(assetId, Const.DataStore.DISPLAY)
        preview.sameElements(original) shouldBe false

        for (
          (dataType, query, bytes) <- List(
            (Const.DataStore.PREVIEW, "?v=2", preview),
            (Const.DataStore.PREVIEW, "?v=1", preview),
            (Const.DataStore.PREVIEW, "", original),
            (Const.DataStore.DISPLAY, "?v=2", display),
            (Const.DataStore.DISPLAY, "?v=1", display)
          )
        )
          withClue(s"[$dataType$query]: ") {
            val response = get(dataType, query)
            response.statusCode shouldBe 200
            response.contentType.head shouldBe "image/webp"
            response.bytes shouldBe bytes
            response.headers("cache-control") shouldBe Seq("private, max-age=31536000, immutable")
            response.headers("x-content-type-options") shouldBe Seq("nosniff")
            response.headers("content-security-policy") shouldBe Seq("sandbox")
          }

        testApp.service.assetEdit.save(assetId, 2, EditRecipe.Identity, testContext.user)
        testApp.service.editRender.render(assetId)
        for (dataType <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)) withClue(s"[$dataType?v=2]: ") {
          val gone = get(dataType, "?v=2")
          gone.statusCode shouldBe 404
          gone.contentType.head should startWith("text/plain")
          gone.headers.contains("cache-control") shouldBe false
        }
        get(Const.DataStore.PREVIEW, "").bytes shouldBe original
    }
  }

  test("A revision above the asset's, or 0, is a 404, and a revision that is no number, or none for a Display image, a 400") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an imported image never edited, and one edited and rendered as revision 1.
     *
     * Assertions:
     *
     * The unedited image's `display?v=1` and `preview?v=1` are plain-text 404s, and so are the edited image's `preview?v=2` and
     * `display?v=2`: a revision not used yet may name other bytes later, so it is never answered, nor cached. A `v` that is not
     * digits, for a Preview or a Display image, and a `display` with no `v` are plain-text 400s saying why, never cached.
     *
     * Edge cases:
     *
     * `v=0` names no edited image: a 404 for the edited image's Preview and Display image alike.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val unedited = testContext.persistAsset().persistedId
    val edited = editedAsset(saves = 1).persistedId
    login()

    withServer(App) {
      host =>
        def get(dataType: String, assetId: String, query: String): requests.Response =
          requests.get(contentUrl(host, repoId, dataType, assetId) + query, cookies = testContext.cookies, check = false)

        def plainError(response: requests.Response, status: Int, message: String): Unit =
          response.statusCode shouldBe status
          response.contentType.head should startWith("text/plain")
          response.headers.contains("cache-control") shouldBe false
          response.text() shouldBe message

        plainError(get(Const.DataStore.DISPLAY, unedited, "?v=1"), 404, "Not found")
        plainError(get(Const.DataStore.PREVIEW, unedited, "?v=1"), 404, "Not found")
        for (dataType <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY); v <- List(2, 0)) withClue(s"[$dataType?v=$v]: ") {
          plainError(get(dataType, edited, s"?v=$v"), 404, "Not found")
        }
        for (bad <- List("x", "-1", "1.5", "1e3", "12345678901")) withClue(s"[v=$bad]: ") {
          plainError(get(Const.DataStore.PREVIEW, edited, s"?v=$bad"), 400, Const.Msg.Err.REVISION_NOT_A_NUMBER)
          plainError(get(Const.DataStore.DISPLAY, edited, s"?v=$bad"), 400, Const.Msg.Err.REVISION_NOT_A_NUMBER)
        }
        plainError(get(Const.DataStore.DISPLAY, edited, ""), 400, Const.Msg.Err.REVISION_REQUIRED)
    }
  }

  test("A Guest is served an edited Preview and a Display image only of an asset Browse shows it") {

    /**
     * Setup:
     *
     * A Library holding two imported images, each edited and rendered: one sorted, one triaged; and a Guest with access to it.
     *
     * Assertions:
     *
     * The Guest gets the sorted asset's `preview?v=1` and `display?v=1`, and a 404 for the triaged one's, as for its original's
     * Preview.
     */
    val library = testContext.persistRepository()
    val repoId = library.persistedId
    val sorted = editedAsset(saves = 1).persistedId
    val triaged = editedAsset(saves = 1, isTriaged = true).persistedId
    val guest = testContext.persistUser(Some(testContext.makeUser().copy(accountType = AccountType.Guest)))
    testContext.grant(guest, library, Permissions.none)

    withServer(App) {
      host =>
        val cookies = cookiesOf(host, guest)
        for (
          (assetId, status) <- List(sorted -> 200, triaged -> 404);
          dataType <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)
        )
          withClue(s"[$dataType of ${if (status == 200) "sorted" else "triaged"}]: ") {
            requests
              .get(contentUrl(host, repoId, dataType, assetId) + "?v=1", cookies = cookies, check = false)
              .statusCode shouldBe status
          }
    }
  }

  private def contentPath(repoId: String, dataType: String, itemId: String): String =
    s"/${Const.DataStore.CONTENT}/r/$repoId/$dataType/$itemId"

  private def contentUrl(host: String, repoId: String, dataType: String, itemId: String): String =
    host + contentPath(repoId, dataType, itemId)

  // Signed by the server's own signer: `testApp` is another instance, which may hold another key
  private def signedUrl(
      host: String,
      repoId: String,
      dataType: String,
      itemId: String,
      expiresAt: Instant = Instant.now.plusSeconds(3600)): String =
    host + App.altitude.service.contentSigner.sign(contentPath(repoId, dataType, itemId), expiresAt)

  // The `sig` of a signed URL, its last query parameter
  private def signature(signed: String): String = signed.substring(signed.indexOf("sig=") + "sig=".length)

  /** The messages the decorators log, at any level, while the block runs; the server runs in this JVM, so it logs here too */
  private def decoratorsLogged(block: => Unit): Seq[String] =
    val logger = LoggerFactory.getLogger(decorators.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    try block
    finally
      logger.detachAppender(appender)
      logger.setLevel(level)
    appender.list.asScala.toSeq.map(_.getFormattedMessage)

  private def fileUrl(host: String, repoId: String, assetId: String): String =
    contentUrl(host, repoId, Const.DataStore.FILE, assetId)

  private def previewUrl(host: String, repoId: String, assetId: String): String =
    contentUrl(host, repoId, Const.DataStore.PREVIEW, assetId)

  private def faceUrl(host: String, repoId: String, faceId: String): String =
    contentUrl(host, repoId, Const.DataStore.FACE, faceId)

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
        val response = requests.get(faceUrl(host, repoId, face.persistedId), cookies = testContext.cookies)
        response.statusCode shouldBe 200
        response.contentType.head should startWith("image/png")
    }
  }
}
