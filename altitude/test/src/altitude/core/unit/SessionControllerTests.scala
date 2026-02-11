package altitude.core.unit

import altitude.core.routes.web.SessionController
import org.scalatest.{DoNotDiscover, funsuite}
import org.scalatest.matchers.should.Matchers.{shouldBe, shouldEqual}

@DoNotDiscover class SessionControllerTests extends funsuite.AnyFunSuite {

  test("isValidRedirectUrl should allow valid relative URLs") {
    SessionController.isValidRedirectUrl("/home") shouldBe true
    SessionController.isValidRedirectUrl("/user/profile") shouldBe true
    SessionController.isValidRedirectUrl("/r/123") shouldBe true
    SessionController.isValidRedirectUrl("/some/path?query=value") shouldBe true
  }

  test("isValidRedirectUrl should reject protocol-relative URLs") {
    SessionController.isValidRedirectUrl("//evil.com") shouldBe false
    SessionController.isValidRedirectUrl("//evil.com/path") shouldBe false
  }

  test("isValidRedirectUrl should reject absolute URLs") {
    SessionController.isValidRedirectUrl("http://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("https://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("ftp://evil.com") shouldBe false
    SessionController.isValidRedirectUrl("javascript://alert(1)") shouldBe false
  }

  test("isValidRedirectUrl should reject URLs with backslashes") {
    SessionController.isValidRedirectUrl("/path\\evil.com") shouldBe false
    SessionController.isValidRedirectUrl("\\evil.com") shouldBe false
  }

  test("isValidRedirectUrl should reject empty URLs") {
    SessionController.isValidRedirectUrl("") shouldBe false
  }

  test("isValidRedirectUrl should reject URLs that don't start with /") {
    SessionController.isValidRedirectUrl("evil.com") shouldBe false
    SessionController.isValidRedirectUrl("path/to/page") shouldBe false
  }
}
