package altitude.core.controller

import altitude.test.IntegrationTestUtil
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.jdk.CollectionConverters.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.AccountType
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.routes.web.SessionController

object ImportControllerTests {

  /** An open WebSocket: its first message, and the status code it is closed with, each when it comes */
  final case class OpenSocket(socket: WebSocket, firstMessage: CompletableFuture[String], closedWith: CompletableFuture[Int])

  /**
   * Opens the WebSocket at `path` (the import status stream's by default) of the server at `host` with `cookies`, and `origin` as
   * the handshake's Origin header when given: the open socket, or the response the handshake was refused with
   */
  def openImportStatus(
      host: String,
      cookies: Map[String, HttpCookie],
      origin: Option[String] = None,
      path: String = "/import/status"): Either[HttpResponse[?], OpenSocket] = {
    val firstMessage = new CompletableFuture[String]()
    val closedWith = new CompletableFuture[Int]()
    val listener = new WebSocket.Listener {
      override def onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
        firstMessage.complete(data.toString)
        // A WebSocket client receives one message per request, the closing one included
        socket.request(1)
        null
      }

      override def onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] = {
        closedWith.complete(statusCode)
        null
      }
    }
    val builder = HttpClient.newHttpClient().newWebSocketBuilder()
    if (cookies.nonEmpty) builder.header("Cookie", cookies.values.map(c => s"${c.getName}=${c.getValue}").mkString("; "))
    origin.foreach(builder.header("Origin", _))

    try {
      val socket = builder.buildAsync(URI.create(s"${host.replace("http://", "ws://")}$path"), listener).get(10, TimeUnit.SECONDS)
      Right(OpenSocket(socket, firstMessage, closedWith))
    } catch {
      case e: ExecutionException =>
        e.getCause match {
          case refused: WebSocketHandshakeException => Left(refused.getResponse)
          case other => throw other
        }
    }
  }

  /** The first message of the WebSocket at `path`, or the status its handshake was refused with */
  def importStatusHandshake(
      host: String,
      cookies: Map[String, HttpCookie],
      origin: Option[String] = None,
      path: String = "/import/status"): Either[Int, String] =
    openImportStatus(host, cookies, origin, path) match {
      case Left(response) => Left(response.statusCode())
      case Right(open) =>
        try Right(open.firstMessage.get(10, TimeUnit.SECONDS))
        finally open.socket.abort()
    }

  /** The session cookie a refused handshake sets, if any */
  def setSessionCookie(response: HttpResponse[?]): Option[HttpCookie] =
    response
      .headers()
      .allValues("set-cookie")
      .asScala
      .flatMap(HttpCookie.parse(_).asScala)
      .find(_.getName == SessionController.AUTH_COOKIE_NAME)
}

@DoNotDiscover class ImportControllerTests extends ControllerTestCore {
  import ImportControllerTests.*

  test("The import status WebSocket opens for a logged-in account and refuses an anonymous handshake") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library.
     *
     * Assertions:
     *
     * The handshake with the session cookie opens the socket, whose first message says it is connected; one with no cookie is
     * refused with a 401 that sets no cookie.
     *
     * Edge cases:
     *
     * A handshake with a cookie that is no token is refused with a 401 that clears the session cookie.
     */
    loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        importStatusHandshake(host, testContext.cookies) shouldBe Right("connected")

        val anonymous = openImportStatus(host, Map.empty).left.toOption.get
        anonymous.statusCode() shouldBe 401
        setSessionCookie(anonymous) shouldBe None

        val forged = testContext.cookies.map((name, cookie) => name -> HttpCookie(cookie.getName, "v2.local.forged"))
        val refused = openImportStatus(host, forged).left.toOption.get
        refused.statusCode() shouldBe 401
        // Undertow writes an empty value quoted
        setSessionCookie(refused).map(cookie => (cookie.getValue.stripPrefix("\"").stripSuffix("\""), cookie.getMaxAge)) shouldBe
          Some(("", 0L))
    }
  }

  test("The import status WebSocket refuses a Guest's handshake") {

    /**
     * Setup:
     *
     * A Guest logged in to a Library.
     *
     * Assertions:
     *
     * The Guest's handshake is refused with a 403: a Guest imports nothing, so it has no import feed.
     */
    loginAs(AccountType.Guest, Permissions.none)

    withServer(App)(host => importStatusHandshake(host, testContext.cookies) shouldBe Left(403))
  }

  test("The import status WebSocket refuses a handshake from another origin") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library.
     *
     * Assertions:
     *
     * A handshake with the session cookie whose Origin is another site is refused with a 403; one whose Origin is the server's
     * own (its scheme, host and port, as the Host header names them) opens, and so does one with no Origin, which only a client
     * that is no browser sends.
     */
    loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        importStatusHandshake(host, testContext.cookies, origin = Some("http://evil.example")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, origin = Some("http://localhost:9999")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, origin = Some("null")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, origin = Some(host)) shouldBe Right("connected")
        importStatusHandshake(host, testContext.cookies) shouldBe Right("connected")
    }
  }

  test("An account's open import status WebSockets close when the account changes or its tokens are revoked") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library, with two import status WebSockets open; the account's version is raised, then
     * the User opens another, and its tokens are revoked.
     *
     * Assertions:
     *
     * The raise closes both sockets with 1013 (try again later), which the client reconnects on, its handshake checking the login
     * again; the revocation closes the third with 1008 (policy violation), which it does not reconnect on.
     */
    loginAs(AccountType.User, Permissions.of(Permission.Import))
    val accountId = testContext.user.persistedId

    withServer(App) {
      host =>
        def open(): OpenSocket = {
          val socket = openImportStatus(host, testContext.cookies).toOption.get
          socket.firstMessage.get(10, TimeUnit.SECONDS) shouldBe "connected"
          socket
        }

        val sockets = List(open(), open())
        testApp.service.accountVersions.bump(accountId)
        for (socket <- sockets) socket.closedWith.get(10, TimeUnit.SECONDS) shouldBe 1013

        val third = open()
        testApp.service.accountVersions.revoke(accountId)
        third.closedWith.get(10, TimeUnit.SECONDS) shouldBe 1008
    }
  }

  test("The Import page connects to the import status WebSocket naming no account") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library.
     *
     * Assertions:
     *
     * The Import page connects to `/import/status` with no query string: the server takes the account from the session.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        val page = requests.get(s"$host/pipeline/r/$repoId", cookies = testContext.cookies).text()
        page.contains("""hx-ws:connect="/import/status"""") shouldBe true
        page.contains("userId") shouldBe false
    }
  }

  test("The Import page renders the upload sections, and their sorting options for a holder of Organize alone") {

    /**
     * Setup:
     *
     * A Library, a User holding Import alone in it and a User holding Import and Organize, each loading the Import page.
     *
     * Assertions:
     *
     * Both get the File upload and Folder upload sections, with the route that opens an Import and the names imports ignore on
     * their root. The User with Organize gets each section's option, "Skip Triage" and "Keep folder structure", with its
     * destination folder field; the User with Import alone gets neither option nor field, so every upload of theirs goes to
     * Triage.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val organizer = testContext.persistUser()
    testContext.grant(organizer, testContext.repository, Permissions.of(Permission.Import, Permission.Organize))
    val options = List("Skip Triage", "Keep folder structure", s"""name="${Api.Field.Import.DEST_FOLDER_ID}"""")

    withServer(App) {
      host =>
        val pages = List(testContext.cookies, cookiesOf(host, organizer)).map(
          cookies => requests.get(s"$host/pipeline/r/$repoId", cookies = cookies).text())

        for (page <- pages) {
          page should include("Choose files")
          page should include("Choose folder")
          page should include(s"""data-app-imports-url="/import/r/$repoId/imports"""")
          page should include("thumbs.db")
        }
        options.filter(pages.head.contains) shouldBe empty
        options.filterNot(pages.last.contains) shouldBe empty
    }
  }

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(200, Millis)))(f)

  private val JSON = Map("Content-Type" -> "application/json")

  /** The bytes of a random image, as a browser would send a file */
  private def imageBytes(): Array[Byte] = IntegrationTestUtil.generateRandomImagBytesBgr(dimensions = 150)

  /** Opens an upload's Import over HTTP with the JSON body, and answers the response */
  private def openImport(host: String, repoId: String, body: ujson.Obj = ujson.Obj()): requests.Response =
    requests.post(
      s"$host/import/r/$repoId/imports",
      cookies = testContext.cookies,
      headers = JSON,
      data = ujson.write(body),
      check = false)

  /** The ID of an upload's Import opened over HTTP */
  private def openedImport(host: String, repoId: String, body: ujson.Obj = ujson.Obj()): String = {
    val response = openImport(host, repoId, body)
    response.statusCode shouldBe 200
    ujson.read(response.text())(Api.Field.ID).str
  }

  /** Sends a file's bytes to an Import under a relative path, and answers the response */
  private def sendFile(host: String, repoId: String, importId: String, relPath: String, bytes: Array[Byte]): requests.Response =
    requests.post(
      s"$host/import/r/$repoId/imports/$importId/files",
      params = Map("relPath" -> relPath),
      cookies = testContext.cookies,
      headers = Map("Content-Type" -> "application/octet-stream"),
      data = bytes,
      check = false
    )

  private def closeImport(host: String, repoId: String, importId: String): requests.Response =
    requests.post(s"$host/import/r/$repoId/imports/$importId/close", cookies = testContext.cookies, check = false)

  test("An upload opens an Import, sends its files one request each and closes it") {

    /**
     * Setup:
     *
     * A User logged in with Import alone in a Library. Over HTTP: an Import is opened under the name "Holiday" with no
     * destination, two images are sent to it, one under the path Beach/sea.jpg and one under its bare name, and it is closed.
     *
     * Assertions:
     *
     * Opening answers the Import's ID, each file is answered as a pending Import file, and closing answers 200. The Import is
     * stored as an upload named "Holiday" with the two files under the paths they were sent with, and once the import queue has
     * taken them it is closed with two imported: both assets are in Triage, in no folder, the first under the name sea.jpg.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        val importId = openedImport(host, repoId, ujson.Obj(Api.Field.Import.NAME -> "Holiday"))

        val sent = List("Beach/sea.jpg", "sand.jpg").map(path => sendFile(host, repoId, importId, path, imageBytes()))
        sent.map(_.statusCode) shouldBe List(200, 200)
        sent.map(response => ujson.read(response.text())(Api.Field.Import.OUTCOME).str) shouldBe List("pending", "pending")
        closeImport(host, repoId, importId).statusCode shouldBe 200

        val imports = testApp.service.imports
        eventually(imports.getById(importId).closedAt should not be empty)
        val closed = imports.getById(importId)
        (closed.source, closed.name, closed.destFolderId, closed.imported) shouldBe (
          ImportSource.Upload,
          Some("Holiday"),
          None,
          2)
        val files = imports.files(importId)
        files.map(_.relPath) shouldBe List("Beach/sea.jpg", "sand.jpg")
        val assets = files.map(file => testApp.service.asset.getById(file.assetId.get))
        assets.map(asset => (asset.isTriaged, asset.folderId)) shouldBe List((true, ""), (true, ""))
        assets.head.fileName shouldBe "sea.jpg"
    }
  }

  test("Opening an Import with a destination takes Organize as well as Import, and an upload takes files from its opener alone") {

    /**
     * Setup:
     *
     * A Library with a folder Sorted and a folder Gone, recycled by a delete. A User holding Import alone in it, and a User
     * holding Import and Organize.
     *
     * Assertions:
     *
     * The User with Import alone is answered 403 for an Import into Sorted, which opens nothing, and 200 for one with no
     * destination. The User with Organize too opens one into Sorted, stored with that destination, and a file sent to it under
     * the path Beach/sea.jpg is imported into Sorted/Beach, sorted.
     *
     * Edge cases:
     *
     * For either User a destination that is no folder of the Library is a 404, and for the User with Organize the folder in the
     * Trash is a 400 that says so; neither opens an Import. The User with Import alone is answered 403 for a file sent to the
     * other User's Import, for a failed file recorded for it and for its close, and the Import holds the one file its opener
     * sent.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val importer = testContext.cookies
    val organizer = testContext.persistUser()
    testContext.grant(organizer, testContext.repository, Permissions.of(Permission.Import, Permission.Organize))
    val sorted = testApp.service.folder.add("Sorted")
    val gone = testApp.service.folder.add("Gone")
    testApp.service.library.deleteFolderById(gone.persistedId)
    val unknown = java.util.UUID.randomUUID.toString
    def into(folderId: String) = ujson.Obj(Api.Field.Import.DEST_FOLDER_ID -> folderId)

    withServer(App) {
      host =>
        openImport(host, repoId, into(sorted.persistedId)).statusCode shouldBe 403
        openImport(host, repoId, into(unknown)).statusCode shouldBe 404
        testApp.service.imports.list(rpp = 10).total shouldBe 0
        openImport(host, repoId).statusCode shouldBe 200

        testContext.cookies = cookiesOf(host, organizer)
        openImport(host, repoId, into(unknown)).statusCode shouldBe 404
        val inTrash = openImport(host, repoId, into(gone.persistedId))
        inTrash.statusCode shouldBe 400
        ujson.read(inTrash.text())(Api.Field.ERROR).str shouldBe Const.Msg.Err.DESTINATION_IN_TRASH
        testApp.service.imports.list(rpp = 10).total shouldBe 1

        val importId = openedImport(host, repoId, into(sorted.persistedId))
        testApp.service.imports.getById(importId).destFolderId shouldBe sorted.id
        sendFile(host, repoId, importId, "Beach/sea.jpg", imageBytes()).statusCode shouldBe 200

        testContext.cookies = importer
        sendFile(host, repoId, importId, "Beach/sneaked.jpg", imageBytes()).statusCode shouldBe 403
        requests
          .post(
            s"$host/import/r/$repoId/imports/$importId/failures",
            cookies = importer,
            headers = JSON,
            data = ujson.write(ujson.Obj(Api.Field.Import.REL_PATH -> "Beach/sneaked.jpg")),
            check = false
          )
          .statusCode shouldBe 403
        closeImport(host, repoId, importId).statusCode shouldBe 403
        testApp.service.imports.getById(importId).isComplete shouldBe false

        testContext.cookies = cookiesOf(host, organizer)
        closeImport(host, repoId, importId).statusCode shouldBe 200
        eventually(testApp.service.imports.getById(importId).closedAt should not be empty)
        testApp.service.imports.files(importId).map(_.relPath) shouldBe List("Beach/sea.jpg")
        val asset = testApp.service.asset.getById(testApp.service.imports.files(importId).head.assetId.get)
        asset.isTriaged shouldBe false
        testApp.service.folder.getPath(asset.folderId).map(_.name) shouldBe List("Sorted", "Beach")
        testContext.cookies = importer
    }
  }

  test("The upload routes refuse an ignored name, a path with \"..\" or an empty name, and an empty file") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library, and an Import opened over HTTP.
     *
     * Assertions:
     *
     * A file sent under a path with a name starting with a dot, an operating system's own file, a download in progress, a "..",
     * an empty name or no path at all is answered 400, and so is a file with no content under a fine name. Recording a file the
     * page could not send is answered 400 for such a path too, and for a body with no path. The Import has no file after all of
     * them.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        val importId = openedImport(host, repoId)
        val bytes = imageBytes()

        for (
          path <- List(
            ".DS_Store",
            "Album/.thumbnails/a.jpg",
            "Thumbs.db",
            "a.jpg.part",
            "Album/../a.jpg",
            "Album//a.jpg",
            "/a.jpg",
            "")
        )
          withClue(s"[$path]: ")(sendFile(host, repoId, importId, path, bytes).statusCode shouldBe 400)
        sendFile(host, repoId, importId, "empty.jpg", Array.emptyByteArray).statusCode shouldBe 400

        def recordFailed(body: ujson.Obj) = requests.post(
          s"$host/import/r/$repoId/imports/$importId/failures",
          cookies = testContext.cookies,
          headers = JSON,
          data = ujson.write(body),
          check = false)
        recordFailed(
          ujson.Obj(Api.Field.Import.REL_PATH -> "Album/.hidden.jpg", Api.Field.Import.SIZE_BYTES -> 10)).statusCode shouldBe 400
        recordFailed(ujson.Obj(Api.Field.Import.SIZE_BYTES -> 10)).statusCode shouldBe 400

        testApp.service.imports.files(importId) shouldBe empty
    }
  }

  test("A file the page could not send is recorded failed, and a closed or unknown Import takes no file") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library, and an Import opened over HTTP.
     *
     * Assertions:
     *
     * Recording a file the page could not send answers it as a failed Import file with its reason, and the Import holds it,
     * failed, under its path and size. Once the Import is closed, a file sent to it and a failed file recorded for it are each
     * answered 409, and the Import still holds the one file.
     *
     * Edge cases:
     *
     * A file sent to, a failed file recorded for and a close of an Import that does not exist are each a 404.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        val importId = openedImport(host, repoId)
        def recordFailed(to: String) = requests.post(
          s"$host/import/r/$repoId/imports/$to/failures",
          cookies = testContext.cookies,
          headers = JSON,
          data = ujson.write(ujson.Obj(Api.Field.Import.REL_PATH -> "Beach/sea.jpg", Api.Field.Import.SIZE_BYTES -> 2048)),
          check = false
        )

        val recorded = recordFailed(importId)
        recorded.statusCode shouldBe 200
        val answer = ujson.read(recorded.text())
        (answer(Api.Field.Import.OUTCOME).str, answer(Api.Field.Import.REASON).str) shouldBe ("failed", "Upload failed")
        def files = testApp.service.imports.files(importId).map(file => (file.relPath, file.sizeBytes, file.outcome))
        files shouldBe List(("Beach/sea.jpg", 2048, ImportOutcome.Failed))

        closeImport(host, repoId, importId).statusCode shouldBe 200
        sendFile(host, repoId, importId, "late.jpg", imageBytes()).statusCode shouldBe 409
        recordFailed(importId).statusCode shouldBe 409
        files.size shouldBe 1

        val unknown = java.util.UUID.randomUUID.toString
        sendFile(host, repoId, unknown, "lost.jpg", imageBytes()).statusCode shouldBe 404
        recordFailed(unknown).statusCode shouldBe 404
        closeImport(host, repoId, unknown).statusCode shouldBe 404
    }
  }
}
