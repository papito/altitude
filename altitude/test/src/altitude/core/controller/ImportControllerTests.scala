package altitude.core.controller

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
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.jdk.CollectionConverters.*

import altitude.core.App
import altitude.core.models.AccountType
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
}
