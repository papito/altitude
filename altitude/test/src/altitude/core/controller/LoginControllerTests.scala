package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }

import altitude.core.App

@DoNotDiscover class LoginControllerTests extends ControllerTestCore {

  test("Valid login") {

    /**
     * Setup:
     *
     * An initialized instance with a user and their repository.
     *
     * Assertions:
     *
     * Logging in with the user's email and password redirects, and the session cookie it sets opens the repository's main page.
     */
    testContext.persistRepository()
    testApp.app.isInitialized = true

    withServer(App) {
      host =>
        val loginResponse = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map(
            "login" -> testContext.user.email,
            "password" -> TestContext.USER_PASSWORD
          ))

        loginResponse.statusCode shouldBe 302

        val indexResponse = requests.get(
          s"$host/r/${testContext.repository.persistedId}",
          cookies = loginResponse.cookies
        )

        indexResponse.statusCode shouldBe 200
        indexResponse.text() should include("<title>ALTITUDE</title>")
    }
  }

  test("Invalid login") {

    /**
     * Setup:
     *
     * An initialized instance with a user and their repository.
     *
     * Assertions:
     *
     * A login is refused with a 401 both for credentials that match no user and for the user's email with a wrong password.
     */
    testContext.persistRepository()
    testApp.app.isInitialized = true

    withServer(App) {
      host =>
        def loginStatus(email: String, password: String): Int =
          requests
            .post(s"$host/login", maxRedirects = 0, check = false, data = Map("login" -> email, "password" -> password))
            .statusCode

        loginStatus("blah", "blahblahblah") shouldBe 401
        loginStatus(testContext.user.email, s"wrong${TestContext.USER_PASSWORD}") shouldBe 401
    }
  }
}
