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
import java.util.concurrent.LinkedBlockingQueue
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
import altitude.core.RequestContext
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.AccountType
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.routes.web.SessionController

object ImportControllerTests {

  /** An open WebSocket: the messages it was sent, in order, and the status code it is closed with, when it comes */
  final case class OpenSocket(socket: WebSocket, messages: LinkedBlockingQueue[String], closedWith: CompletableFuture[Int]) {

    /** The next message the socket was sent, waiting for it */
    def next(): String =
      Option(messages.poll(60, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("The socket was sent no message"))
  }

  /** The path of a Library's import status stream */
  def statusPath(repoId: String): String = s"/import/r/$repoId/status"

  /**
   * Opens the WebSocket at `path` (a Library's import status stream, [[statusPath]]) of the server at `host` with `cookies`, and
   * `origin` as the handshake's Origin header when given: the open socket, or the response the handshake was refused with
   */
  def openImportStatus(
      host: String,
      cookies: Map[String, HttpCookie],
      path: String,
      origin: Option[String] = None): Either[HttpResponse[?], OpenSocket] = {
    val messages = new LinkedBlockingQueue[String]()
    val closedWith = new CompletableFuture[Int]()
    val listener = new WebSocket.Listener {
      // A message can come in parts
      private val parts = new StringBuilder

      override def onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
        parts.append(data)
        if (last) {
          messages.add(parts.toString)
          parts.clear()
        }
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
      Right(OpenSocket(socket, messages, closedWith))
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
      path: String,
      origin: Option[String] = None): Either[Int, String] =
    openImportStatus(host, cookies, path, origin) match {
      case Left(response) => Left(response.statusCode())
      case Right(open) =>
        try Right(open.next())
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

  private val CONNECTED = ImportStatusWsActor.CONNECTED

  test("A Library's import status WebSocket opens for an account holding Import there and refuses an anonymous handshake") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library.
     *
     * Assertions:
     *
     * The handshake with the session cookie opens the Library's socket, whose first message says it is connected; one with no
     * cookie is refused with a 401 that sets no cookie.
     *
     * Edge cases:
     *
     * A handshake with a cookie that is no token is refused with a 401 that clears the session cookie.
     */
    val path = statusPath(loginAs(AccountType.User, Permissions.of(Permission.Import)))

    withServer(App) {
      host =>
        importStatusHandshake(host, testContext.cookies, path) shouldBe Right(CONNECTED)

        val anonymous = openImportStatus(host, Map.empty, path).left.toOption.get
        anonymous.statusCode() shouldBe 401
        setSessionCookie(anonymous) shouldBe None

        val forged = testContext.cookies.map((name, cookie) => name -> HttpCookie(cookie.getName, "v2.local.forged"))
        val refused = openImportStatus(host, forged, path).left.toOption.get
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
    val path = statusPath(loginAs(AccountType.Guest, Permissions.none))

    withServer(App)(host => importStatusHandshake(host, testContext.cookies, path) shouldBe Left(403))
  }

  test("A Library's import status WebSocket refuses an account that does not hold Import there") {

    /**
     * Setup:
     *
     * A Library, and three Users: one holding every Permission in it but Import, one holding Import in another Library and no
     * grant in this one, and one holding Import alone in it.
     *
     * Assertions:
     *
     * The handshake of the User without Import is refused with a 403. The handshake of the User with no grant in the Library is
     * refused with a 404, as one for a Library that does not exist is. The handshake of the User holding Import opens.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val library = testContext.repository
    val withoutImport = testContext.persistUser()
    testContext.grant(withoutImport, library, Permissions(Permissions.all.mask & ~Permissions.of(Permission.Import).mask))
    val stranger = testContext.persistUser()
    testContext.grant(stranger, testContext.persistRepository(), Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        importStatusHandshake(host, cookiesOf(host, withoutImport), statusPath(repoId)) shouldBe Left(403)
        importStatusHandshake(host, cookiesOf(host, stranger), statusPath(repoId)) shouldBe Left(404)
        importStatusHandshake(host, testContext.cookies, statusPath(java.util.UUID.randomUUID.toString)) shouldBe Left(404)
        importStatusHandshake(host, testContext.cookies, statusPath(repoId)) shouldBe Right(CONNECTED)
    }
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
    val path = statusPath(loginAs(AccountType.User, Permissions.of(Permission.Import)))

    withServer(App) {
      host =>
        importStatusHandshake(host, testContext.cookies, path, origin = Some("http://evil.example")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, path, origin = Some("http://localhost:9999")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, path, origin = Some("null")) shouldBe Left(403)
        importStatusHandshake(host, testContext.cookies, path, origin = Some(host)) shouldBe Right(CONNECTED)
        importStatusHandshake(host, testContext.cookies, path) shouldBe Right(CONNECTED)
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
    val path = statusPath(loginAs(AccountType.User, Permissions.of(Permission.Import)))
    val accountId = testContext.user.persistedId

    withServer(App) {
      host =>
        def open(): OpenSocket = {
          val socket = openImportStatus(host, testContext.cookies, path).toOption.get
          socket.next() shouldBe CONNECTED
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

  test("The Import page connects to its Library's import status WebSocket naming no account") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library.
     *
     * Assertions:
     *
     * The Import page connects to the Library's import status stream with no query string: the server takes the account from the
     * session.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        val page = requests.get(s"$host/pipeline/r/$repoId", cookies = testContext.cookies).text()
        page.contains(s"""hx-ws:connect="${statusPath(repoId)}"""") shouldBe true
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

  test("Opening an Import takes the number of files the upload is about to send, a whole number from 0") {

    /**
     * Setup:
     *
     * An account holding Import in a Library, logged in.
     *
     * Assertions:
     *
     * An Import opened with 24 as its expected files is stored expecting 24, one opened with 0 expecting none, and one opened
     * with no number, or with a null one, with none. The Import history titles the first "24 files" and counts 24 remaining,
     * before any file is sent.
     *
     * Edge cases:
     *
     * A negative number, a fraction, a number past what an Import counts, a number written as text and a flag are each refused
     * with a 400 that names the field, and open nothing.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    def expecting(value: ujson.Value) = ujson.Obj(Api.Field.Import.EXPECTED_FILES -> value)

    withServer(App) {
      host =>
        val expectsMany = openedImport(host, repoId, expecting(24))
        val expectsNone = openedImport(host, repoId, expecting(0))
        val unknowing = openedImport(host, repoId)
        val nullified = openedImport(host, repoId, expecting(ujson.Null))
        List(expectsMany, expectsNone, unknowing, nullified).map(imports.getById(_).expectedFiles) shouldBe
          List(Some(24), Some(0), None, None)

        val history = partial(host, s"$repoId/history").text()
        val entry = history.split(s"""data-import-id="$expectsMany"""")(1).split("""data-import-id="""")(0)
        (entry should include).regex(""">24\s+files</strong>""")
        entry should include("""<li class="pending">24 remaining</li>""")

        for (refused <- List[ujson.Value](-1, 1.5, 3000000000L, "24", true)) withClue(s"[$refused]: ") {
          val response = openImport(host, repoId, expecting(refused))
          response.statusCode shouldBe 400
          response.text() should include(Api.Field.Import.EXPECTED_FILES)
        }
        imports.list(rpp = 10).total shouldBe 4
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

  test("A file sent to an upload whose destination was recycled is answered as failed, and nothing of it is staged or imported") {

    /**
     * Setup:
     *
     * A User logged in with Import and Organize in a Library, and an Import opened over HTTP into the folder Sorted, which is
     * then deleted, so it is in the Trash. Two images are sent to the Import.
     *
     * Assertions:
     *
     * Each file is answered 200 as a failed Import file, saying that the destination folder is in the Trash, so the page goes on
     * with the next and sends neither again. The Import holds both, failed with that reason and pointing at no asset, and nothing
     * pending: closing it closes it at once, since no file went to the import queue. The server's staging directory holds no file
     * more than before.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import, Permission.Organize))
    val sorted = testApp.service.folder.add("Sorted")
    def stagedFiles: Int = Option(App.altitude.service.staging.dir.toFile.listFiles).fold(0)(_.length)

    withServer(App) {
      host =>
        val importId = openedImport(host, repoId, ujson.Obj(Api.Field.Import.DEST_FOLDER_ID -> sorted.persistedId))
        testApp.service.library.deleteFolderById(sorted.persistedId)
        val stagedBefore = stagedFiles

        val sent = List("Beach/sea.jpg", "sand.jpg").map(path => sendFile(host, repoId, importId, path, imageBytes()))

        sent.map(_.statusCode) shouldBe List(200, 200)
        sent
          .map(response => ujson.read(response.text()))
          .map(answer => (answer(Api.Field.Import.OUTCOME).str, answer(Api.Field.Import.REASON).str)) shouldBe List.fill(2)(
          ("failed", Const.Msg.Err.DESTINATION_IN_TRASH))
        stagedFiles shouldBe stagedBefore

        val imports = testApp.service.imports
        imports.files(importId).map(file => (file.relPath, file.outcome, file.reason, file.assetId)) shouldBe List(
          ("Beach/sea.jpg", ImportOutcome.Failed, Some(Const.Msg.Err.DESTINATION_IN_TRASH), None),
          ("sand.jpg", ImportOutcome.Failed, Some(Const.Msg.Err.DESTINATION_IN_TRASH), None)
        )
        closeImport(host, repoId, importId).statusCode shouldBe 200
        val closed = imports.getById(importId)
        (closed.pending, closed.failed) shouldBe (0, 2)
        closed.closedAt should not be empty
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

  private def imports = testApp.service.imports

  /** An htmx partial of the Import history, as the logged-in account gets it */
  private def partial(host: String, path: String, params: Map[String, String] = Map.empty): requests.Response =
    requests.get(s"$host/htmx/import/r/$path", params = params, cookies = testContext.cookies, check = false)

  // What opens a file's path in the files partial
  private val PATH = """<span class="path">"""

  /** How many times `part` occurs in `text` */
  private def occurrences(text: String, part: String): Int = text.sliding(part.length).count(_ == part)

  test("The Import history lists the Library's Imports with who delivered each, where it went and its counts") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library with a folder Clients and its subfolder Wedding. The User's upload of the folder
     * "Wedding 2026" into Wedding is closed with two files imported, one already in the Library and one failed; a Watch folder's
     * scan "Phone sync", which no account delivers, is closed with one file imported; the User's upload of files is in progress
     * with one file imported and one pending. Another Library has an Import "Theirs".
     *
     * Assertions:
     *
     * The history partial holds the three Imports, the upload of files under "In progress" and the other two under "Earlier". The
     * folder upload is titled by its name, with the User's name, its destination's path and its counts by outcome, the duplicate
     * as "already in the Library"; the scan by its name, delivered by "Watch folder" into Triage; the upload of files by its
     * number of files, with its files remaining. The Import page holds the same history.
     *
     * Edge cases:
     *
     * The other Library's Import is in neither. A Library with no Import says that nothing was imported yet, in the partial and
     * on the page.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val clients = testApp.service.folder.add("Clients")
    val wedding = testApp.service.folder.add("Wedding", parentId = clients.id)

    def fileOf(importId: String, path: String, outcome: ImportOutcome, reason: Option[String] = None): Unit =
      imports.settle(imports.addFile(importId, path, sizeBytes = 10).persistedId, outcome, reason)

    val folderUpload = imports.open(ImportSource.Upload, name = Some("Wedding 2026"), destFolderId = wedding.id).persistedId
    fileOf(folderUpload, "a.jpg", ImportOutcome.Imported)
    fileOf(folderUpload, "b.jpg", ImportOutcome.Imported)
    fileOf(folderUpload, "c.jpg", ImportOutcome.Duplicate, Some("Triage"))
    fileOf(folderUpload, "d.jpg", ImportOutcome.Failed, Some("Cannot decode image"))
    imports.close(folderUpload)

    val scan =
      RequestContext.account.withValue(None)(imports.open(ImportSource.WatchFolder, name = Some("Phone sync")).persistedId)
    fileOf(scan, "IMG_0001.jpg", ImportOutcome.Imported)
    imports.close(scan)

    val filesUpload = imports.open(ImportSource.Upload).persistedId
    fileOf(filesUpload, "one.jpg", ImportOutcome.Imported)
    imports.addFile(filesUpload, "two.jpg", sizeBytes = 10)

    val first = testContext.repositories.head
    testApp.service.repository.switchContextToRepository(testContext.persistRepository())
    imports.open(ImportSource.Upload, name = Some("Theirs"))
    // Adding a Library makes it the context's
    val empty = addLibrary(s"Empty ${java.util.UUID.randomUUID}", testContext.user)
    testApp.service.repository.switchContextToRepository(first)

    withServer(App) {
      host =>
        val history = partial(host, s"$repoId/history")
        history.statusCode shouldBe 200
        val page = requests.get(s"$host/pipeline/r/$repoId", cookies = testContext.cookies).text()

        for (shown <- List(history.text(), page)) {
          occurrences(shown, "data-import-id=") shouldBe 3
          shown.indexOf("In progress") should be < shown.indexOf(s"""data-import-id="$filesUpload"""")
          shown.indexOf(s"""data-import-id="$filesUpload"""") should be < shown.indexOf("Earlier")
          shown.indexOf("Earlier") should be < shown.indexOf(s"""data-import-id="$scan"""")
          shown.indexOf(s"""data-import-id="$scan"""") should be < shown.indexOf(s"""data-import-id="$folderUpload"""")

          for (
            said <- List(
              "Wedding 2026",
              testContext.user.name,
              "Clients › Wedding",
              "2 imported",
              "1 already in the Library",
              "1 failed",
              "Phone sync",
              "Watch folder",
              "into Triage",
              "2 files",
              "1 remaining"
            )
          ) shown should include(said)
          (shown should not).include("Theirs")
          (shown should not).include("Nothing imported yet")
        }

        for (
          nothing <- List(
            partial(host, s"${empty.persistedId}/history").text(),
            requests.get(s"$host/pipeline/r/${empty.persistedId}", cookies = testContext.cookies).text())
        ) {
          nothing should include("Nothing imported yet")
          (nothing should not).include("data-import-id=")
        }
    }
  }

  test("The Import history shows a number of Imports at a time and offers the earlier ones") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library holding one Import more than the history shows at first.
     *
     * Assertions:
     *
     * The history partial shows as many Imports as it shows at first, says how many there are, and offers the earlier ones by the
     * route that shows one step more. Asked for that many, it shows every Import and offers nothing.
     *
     * Edge cases:
     *
     * Asked for fewer Imports than it shows at first, it shows as many as it shows at first.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val step = Const.ImportHistory.IMPORTS_STEP
    for (_ <- 0 to step) imports.close(imports.open(ImportSource.Upload).persistedId)

    withServer(App) {
      host =>
        val top = partial(host, s"$repoId/history").text()
        occurrences(top, "data-import-id=") shouldBe step
        top should include(s"Showing $step of ${step + 1} Imports")
        top should include(s"/htmx/import/r/$repoId/history?shown=${step * 2}")

        val all = partial(host, s"$repoId/history", Map("shown" -> (step * 2).toString)).text()
        occurrences(all, "data-import-id=") shouldBe step + 1
        (all should not).include("Showing")

        occurrences(partial(host, s"$repoId/history", Map("shown" -> "1")).text(), "data-import-id=") shouldBe step
    }
  }

  test("An Import's files are listed by outcome a number at a time, an imported one linking to its asset") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library, whose Import holds a file of every outcome: a.jpg imported, with its asset;
     * b.jpg already in the Library, in the folder Clients/Wedding; c.pdf unsupported; d.jpg failed, undecodable; e.jpg imported,
     * its asset gone; and as many pending files as the list shows at first. Another Library has an Import.
     *
     * Assertions:
     *
     * The files partial shows the first files in path order, as many as it shows at first, says how many the Import has and
     * offers more by the route that shows one step more, which shows them all and offers nothing. Its filter offers every file
     * and each outcome with its number of files. a.jpg links to its asset's page; e.jpg, whose asset is gone, links nowhere;
     * b.jpg says where the Library has it; d.jpg says why it failed. Filtered by an outcome, it shows that outcome's files alone
     * and their number.
     *
     * Edge cases:
     *
     * An outcome that is none is a 400. The other Library's Import and an unknown one are each a 404.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val step = Const.ImportHistory.FILES_STEP
    val asset = testContext.insertAsset(testContext.makeAsset())
    val importId = imports.open(ImportSource.Upload).persistedId
    def fileOf(path: String) = imports.addFile(importId, path, sizeBytes = 10).persistedId

    imports.settle(fileOf("a.jpg"), ImportOutcome.Imported, assetId = asset.id)
    imports.settle(fileOf("b.jpg"), ImportOutcome.Duplicate, reason = Some("Clients/Wedding"))
    imports.settle(fileOf("c.pdf"), ImportOutcome.Unsupported, reason = Some("Unsupported media type"))
    imports.settle(fileOf("d.jpg"), ImportOutcome.Failed, reason = Some("Cannot decode image"))
    imports.settle(fileOf("e.jpg"), ImportOutcome.Imported)
    for (n <- 1 to step) fileOf(f"pending/$n%03d.jpg")
    val total = step + 5

    val first = testContext.repositories.head
    testApp.service.repository.switchContextToRepository(testContext.persistRepository())
    val theirs = imports.open(ImportSource.Upload).persistedId
    testApp.service.repository.switchContextToRepository(first)

    withServer(App) {
      host =>
        val files = s"$repoId/imports/$importId/files"
        val top = partial(host, files)
        top.statusCode shouldBe 200
        val shown = top.text()

        occurrences(shown, PATH) shouldBe step
        shown should include(s"Showing $step of $total files")
        shown should include(s"/htmx/import/r/$files?shown=${step * 2}")
        for (path <- List("a.jpg", "b.jpg", "c.pdf", "d.jpg", "e.jpg", f"pending/${step - 5}%03d.jpg"))
          shown should include(s"$PATH$path<")
        (shown should not).include(f"${PATH}pending/${step - 4}%03d.jpg<")
        for (
          filter <- List(s"All $total", "imported 2", "already in the Library 1", "unsupported 1", "failed 1", s"pending $step")
        )
          shown should include(filter)

        occurrences(shown, s"/r/$repoId/asset/") shouldBe 1
        shown should include(s"""href="/r/$repoId/asset/${asset.persistedId}"""")
        shown should include("Already in the Library: Clients/Wedding")
        shown should include("Cannot decode image")

        val all = partial(host, files, Map("shown" -> (step * 2).toString)).text()
        occurrences(all, PATH) shouldBe total
        (all should not).include("Showing")

        val failed = partial(host, files, Map("outcome" -> "failed")).text()
        occurrences(failed, PATH) shouldBe 1
        failed should include(s"${PATH}d.jpg<")
        failed should include(s"/htmx/import/r/$files?outcome=failed")

        partial(host, files, Map("outcome" -> "lost")).statusCode shouldBe 400
        partial(host, s"$repoId/imports/$theirs/files").statusCode shouldBe 404
        partial(host, s"$repoId/imports/${java.util.UUID.randomUUID}/files").statusCode shouldBe 404
    }
  }

  test("A file or folder name holding markup is escaped in the Import history and in an Import's files") {

    /**
     * Setup:
     *
     * A User logged in with Import in a Library, whose Import is named by an uploaded folder whose name is a script element, and
     * holds a file whose name is an image element with a handler, failed for a reason that holds markup too.
     *
     * Assertions:
     *
     * The history partial and the Import page hold the Import's name with its markup escaped, and no script element of that name.
     * The files partial holds the file's name and its reason escaped, and no image element.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val importId = imports.open(ImportSource.Upload, name = Some("<script>alert(1)</script>")).persistedId
    val file = imports.addFile(importId, "<img src=x onerror=alert(1)>.jpg", sizeBytes = 10)
    imports.settle(file.persistedId, ImportOutcome.Failed, reason = Some("<b>bold</b>"))

    withServer(App) {
      host =>
        for (
          shown <- List(
            partial(host, s"$repoId/history").text(),
            requests.get(s"$host/pipeline/r/$repoId", cookies = testContext.cookies).text())
        ) {
          shown should include("&lt;script&gt;alert(1)&lt;/script&gt;")
          (shown should not).include("<script>alert(1)")
        }

        val files = partial(host, s"$repoId/imports/$importId/files").text()
        files should include("&lt;img src=x onerror=alert(1)&gt;.jpg")
        files should include("&lt;b&gt;bold&lt;/b&gt;")
        (files should not).include("<img")
        (files should not).include("<b>")
    }
  }

  test("An upload's progress is pushed to every import status socket of its Library, and to no other Library's") {

    /**
     * Setup:
     *
     * A Library with two Users holding Import, each with an import status socket open, and a third User holding Import in another
     * Library, with a socket open on that one. Over HTTP the first User opens an Import named by a folder whose name holds
     * markup, sends it one image and closes it.
     *
     * Assertions:
     *
     * Both sockets of the upload's Library are sent the Import's status as `hx-ws` takes it: every message replaces the Import's
     * status element, and the last one says the Import is closed, with its one file imported and none remaining. The Import's
     * name comes escaped, in the title sent beside the status.
     *
     * Edge cases:
     *
     * The socket on the other Library is sent nothing of it.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import))
    val watcher = testContext.persistUser()
    testContext.grant(watcher, testContext.repository, Permissions.of(Permission.Import))
    val elsewhere = testContext.persistRepository()
    val outsider = testContext.persistUser()
    testContext.grant(outsider, elsewhere, Permissions.of(Permission.Import))

    withServer(App) {
      host =>
        def socket(cookies: Map[String, HttpCookie], library: String): OpenSocket = {
          val opened = openImportStatus(host, cookies, statusPath(library)).toOption.get
          opened.next() shouldBe CONNECTED
          opened
        }
        val mine = socket(testContext.cookies, repoId)
        val theirs = socket(cookiesOf(host, watcher), repoId)
        val outside = socket(cookiesOf(host, outsider), elsewhere.persistedId)

        val importId = openedImport(host, repoId, ujson.Obj(Api.Field.Import.NAME -> "<b>Holiday</b>"))
        sendFile(host, repoId, importId, "sea.jpg", imageBytes()).statusCode shouldBe 200
        closeImport(host, repoId, importId).statusCode shouldBe 200

        for (told <- List(mine, theirs)) {
          // The socket's messages up to the one that says the Import is closed
          val statuses = scala.collection.mutable.ListBuffer(ujson.read(told.next()))
          while (!statuses.last(Api.Field.ImportStatus.IS_CLOSED).bool) statuses += ujson.read(told.next())

          for (status <- statuses) {
            status(Api.Field.ImportStatus.IMPORT_ID).str shouldBe importId
            status(Api.Field.ImportStatus.TARGET).str shouldBe s"#${Const.ImportHistory.statusId(importId)}"
            status(Api.Field.ImportStatus.SWAP).str shouldBe "outerHTML"
          }
          val content = statuses.last(Api.Field.ImportStatus.CONTENT).str
          content should include("1 imported")
          (content should not).include("remaining")
          content should include("&lt;b&gt;Holiday&lt;/b&gt;")
          (content should not).include("<b>")
        }

        mine.messages.asScala.toList shouldBe empty
        theirs.messages.asScala.toList shouldBe empty
        outside.messages.asScala.toList shouldBe empty
    }
  }
}
