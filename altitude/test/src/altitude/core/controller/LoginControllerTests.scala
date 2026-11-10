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

  /** Where a login with the test context's user's password sends them */
  private def loginLocation(host: String): Seq[String] = {
    val response = requests.post(
      s"$host/login",
      maxRedirects = 0,
      check = false,
      data = Map("login" -> testContext.user.email, "password" -> TestContext.USER_PASSWORD))
    response.statusCode shouldBe 302
    response.headers("location")
  }

  test("A login lands in the last active Library") {

    /**
     * Setup:
     *
     * A user with Libraries A, B and C, B recorded as the last active one.
     *
     * Assertions:
     *
     * The login redirects to B, neither the first Library by name nor the newest.
     */
    val user = testContext.persistUser()
    addLibrary("Library A", user)
    val b = addLibrary("Library B", user)
    addLibrary("Library C", user)
    testApp.service.user.rememberLastActiveRepository(user, b.persistedId)

    withServer(App)(host => loginLocation(host) shouldBe Seq(s"/r/${b.persistedId}"))
  }

  test("A login without a last active Library lands in the first owned by name and records it") {

    /**
     * Setup:
     *
     * A user with Libraries "Client 10" and "Client 2", the second added last, and no last active Library.
     *
     * Assertions:
     *
     * The login redirects to "Client 2" rather than failing, and records it as the last active one.
     */
    val user = testContext.persistUser()
    addLibrary("Client 10", user)
    val firstByName = addLibrary("Client 2", user)

    withServer(App) {
      host =>
        loginLocation(host) shouldBe Seq(s"/r/${firstByName.persistedId}")
        lastActiveRepoIdOf(user) shouldBe Some(firstByName.persistedId)
    }
  }
}
