package altitude.core.unit

import altitude.test.TestFocus
import java.nio.file.Files
import java.time.Instant
import org.apache.commons.io.FileUtils
import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.{ shouldBe, shouldEqual }

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.Environment
import altitude.core.routes.web.SessionController
import altitude.core.util.Util

@DoNotDiscover class SessionControllerTests extends funsuite.AnyFunSuite with TestFocus {

  test("isValidRedirectUrl should allow valid relative URLs") {

    /**
     * Setup:
     *
     * Relative paths of one and two segments, a repository path and a path with a query string.
     *
     * Assertions:
     *
     * Every one is accepted as a safe redirect target.
     */
    SessionController.isValidRedirectUrl("/home") shouldBe true
    SessionController.isValidRedirectUrl("/user/profile") shouldBe true
    SessionController.isValidRedirectUrl("/r/123") shouldBe true
    SessionController.isValidRedirectUrl("/some/path?query=value") shouldBe true
  }

  test("isValidRedirectUrl should reject protocol-relative URLs") {

    /**
     * Setup:
     *
     * Protocol-relative URLs to a foreign host, with and without a path.
     *
     * Assertions:
     *
     * Both are rejected, even though they start with a slash, since a browser follows them off-site.
     */
    SessionController.isValidRedirectUrl("//evil.com") shouldBe false
    SessionController.isValidRedirectUrl("//evil.com/path") shouldBe false
  }

  test("isValidRedirectUrl should reject absolute URLs") {

    /**
     * Setup:
     *
     * Absolute URLs with http, https, ftp and javascript schemes.
     *
     * Assertions:
     *
     * Every one is rejected as a redirect target, whatever the scheme.
     */
    SessionController.isValidRedirectUrl("http://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("https://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("ftp://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("javascript://alert(1)") shouldBe false
  }

  test("isValidRedirectUrl should reject URLs with backslashes") {

    /**
     * Setup:
     *
     * A relative path with a backslash in it and a URL that starts with one.
     *
     * Assertions:
     *
     * Both are rejected, since browsers can read a backslash as a slash and leave the site.
     */
    SessionController.isValidRedirectUrl("/path\\evil.com") shouldBe false
    SessionController.isValidRedirectUrl("\\evil.com") shouldBe false
  }

  test("isValidRedirectUrl should reject empty URLs") {

    /**
     * Setup:
     *
     * An empty URL.
     *
     * Assertions:
     *
     * It is not a valid redirect target.
     */
    SessionController.isValidRedirectUrl("") shouldBe false
  }

  test("isValidRedirectUrl should reject URLs that don't start with /") {

    /**
     * Setup:
     *
     * A bare host name and a path with no leading slash.
     *
     * Assertions:
     *
     * Both are rejected, since only root-relative paths are accepted.
     */
    SessionController.isValidRedirectUrl("evil.com") shouldBe false
    SessionController.isValidRedirectUrl("path/to/page") shouldBe false
  }

  test("The session cookie is Secure unless server.secure_cookies is false") {

    /**
     * Setup:
     *
     * The config of a start with no variable set, and of one with `ALTITUDE_SECURE_COOKIES=false`; a session cookie and the
     * cookie that clears it, each built as that config says.
     *
     * Assertions:
     *
     * By default both cookies are `Secure`. With the variable false neither is, so a browser on a plain HTTP address keeps the
     * session and takes its clearing too.
     *
     * Edge cases:
     *
     * `HttpOnly`, `SameSite=Strict` and the path are the same either way.
     */
    val root = Files.createTempDirectory("altitude-cookie-test")
    val (byDefault, overHttp) =
      try {
        def secureCookies(variables: Map[String, String]): Boolean =
          Altitude.loadConfig(Environment.Name.PROD, variables, root.toString).getBoolean(Const.Conf.SERVER_SECURE_COOKIES)

        (secureCookies(Map.empty), secureCookies(Map("ALTITUDE_SECURE_COOKIES" -> "false")))
      } finally FileUtils.deleteDirectory(root.toFile)

    byDefault shouldBe true
    overHttp shouldBe false

    val expiresAt = Instant.now.plusSeconds(3600)
    for (secure <- Seq(byDefault, overHttp)) {
      val session = SessionController.authCookie("token", expiresAt, secure)
      val cleared = SessionController.authCookie("", Instant.EPOCH, secure)

      for (cookie <- Seq(session, cleared)) {
        cookie.secure shouldBe secure
        cookie.httpOnly shouldBe true
        cookie.sameSite shouldBe "Strict"
        cookie.path shouldBe "/"
        cookie.name shouldBe SessionController.AUTH_COOKIE_NAME
      }
      cleared.maxAge shouldBe 0
    }
  }

  test("A login is logged and audited printable: control characters escaped, cut to a length") {

    /**
     * Setup:
     *
     * A login with a carriage return and a line feed before text that reads like a log line, one with a NUL, and one of 100
     * characters.
     *
     * Assertions:
     *
     * `Util.printable` escapes each control character as `\uXXXX`, so the text cannot start a log line or carry a NUL, and cuts
     * the result to the given length.
     */
    Util.printable("jane\r\nINFO forged", 64) shouldBe "jane\\u000d\\u000aINFO forged"
    Util.printable("jane\u0000", 64) shouldBe "jane\\u0000"
    Util.printable("x" * 100, 64) shouldBe "x" * 64
  }
}
