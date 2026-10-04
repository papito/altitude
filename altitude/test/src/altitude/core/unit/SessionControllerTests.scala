package altitude.core.unit

import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.{ shouldBe, shouldEqual }

import altitude.core.routes.web.SessionController

@DoNotDiscover class SessionControllerTests extends funsuite.AnyFunSuite {

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
}
