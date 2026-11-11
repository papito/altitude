package altitude.core.controller

import altitude.test.TestContext
import java.net.HttpCookie
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe }

import scala.jdk.CollectionConverters.*

import altitude.core.App
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.User
import altitude.core.routes.web.SessionController
import altitude.core.util.Util

@DoNotDiscover class LoginControllerTests extends ControllerTestCore {

  test("Valid login") {

    /**
     * Setup:
     *
     * An initialized instance with a User and a repository the User holds a grant in.
     *
     * Assertions:
     *
     * Logging in with the user's email and password redirects, and the session cookie it sets opens the repository's main page,
     * titled with its name.
     */
    testContext.persistRepository()
    testContext.grant(testContext.user, testContext.repository)
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
        indexResponse.text() should include(s"<title>\u2068${testContext.repository.name}\u2069 · Altitude</title>")
    }
  }

  test("Invalid login") {

    /**
     * Setup:
     *
     * An initialized instance with a User and a repository.
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
     * A User with grants in Libraries A, B and C, B recorded as the last active one.
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

  test("A login without a last active Library lands in the first it can see by name and records it") {

    /**
     * Setup:
     *
     * A User with grants in Libraries "Client 10" and "Client 2", the second added last, and no last active Library.
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

  /** The status of a request for `path`, with the session cookie or a Bearer token, and the session cookie it sets, if any */
  private def request(
      host: String,
      path: String,
      cookies: Map[String, HttpCookie] = Map.empty,
      bearer: Option[String] = None,
      json: Boolean = false): (Int, Option[HttpCookie]) = {
    val headers = bearer.map(token => "Authorization" -> s"Bearer $token").toMap ++
      (if (json) Map("Accept" -> "application/json") else Map.empty)
    val response = requests.get(s"$host$path", cookies = cookies, headers = headers, maxRedirects = 0, check = false)
    (response.statusCode, response.cookies.get(SessionController.AUTH_COOKIE_NAME))
  }

  /** Logs in by `login` with the fixtures' password: the session cookies */
  private def loginBy(host: String, login: String): Map[String, HttpCookie] = {
    val response = requests.post(
      s"$host/login",
      maxRedirects = 0,
      check = false,
      data = Map("login" -> login, "password" -> TestContext.USER_PASSWORD))
    response.statusCode shouldBe 302
    response.cookies
  }

  private def setActive(account: User, active: Boolean): Unit =
    testApp.txManager.withTransaction {
      testApp.DAO.user.updateByBySql("UPDATE account SET is_active = ? WHERE id = ?", List(active, account.persistedId))
    }

  test("A login by username, in any case, opens the account's Library") {

    /**
     * Setup:
     *
     * A User holding a grant in a Library, with a username in mixed case.
     *
     * Assertions:
     *
     * Logging in with the username in upper case redirects to the Library, and the cookie it sets opens the Library's page.
     */
    val user = testContext.persistUser(username = Some(s"Mixed.Case.${Util.randomStr(8)}"))
    val library = addLibrary("Library A", user)

    withServer(App) {
      host =>
        val cookies = loginBy(host, user.username.toUpperCase)
        request(host, s"/r/${library.persistedId}", cookies)._1 shouldBe 200
    }
  }

  test("A deactivated account's login is refused with the 401 of a wrong password") {

    /**
     * Setup:
     *
     * A User holding a grant in a Library, deactivated by hand-written SQL.
     *
     * Assertions:
     *
     * Logging in with its email and its right password answers 401 with the login page, as a wrong password does, and sets no
     * session cookie.
     */
    val user = testContext.persistUser()
    addLibrary("Library A", user)
    setActive(user, active = false)

    withServer(App) {
      host =>
        def attempt(password: String) =
          requests.post(
            s"$host/login",
            maxRedirects = 0,
            check = false,
            data = Map("login" -> user.email, "password" -> password))

        val refused = attempt(TestContext.USER_PASSWORD)
        val wrong = attempt(s"wrong${TestContext.USER_PASSWORD}")
        refused.statusCode shouldBe 401
        refused.text() shouldBe wrong.text()
        refused.cookies.get(SessionController.AUTH_COOKIE_NAME) shouldBe None
    }
  }

  test("The login form names its field Email or username") {

    /**
     * Setup:
     *
     * The login page.
     *
     * Assertions:
     *
     * The login field is labelled "Email or username".
     */
    withServer(App) {
      host =>
        val page = requests.get(s"$host/login").text()
        page should include("""<label for="login">Email or username</label>""")
        page should include("""id="login"""")
    }
  }

  test("A change to an account reaches its session at the next request, which replaces the cookie") {

    /**
     * Setup:
     *
     * A User logged in while granted access to Library A alone, then granted every Permission in Library B, which raises the
     * account's version.
     *
     * Assertions:
     *
     * The session's next request, for B's page, answers 200 and sets a new session cookie, which opens B and sets none.
     *
     * Edge cases:
     *
     * Before the grant, B's page is a 404.
     */
    val user = testContext.persistUser()
    addLibrary("Library A", user)
    val b = testContext.persistRepository()

    withServer(App) {
      host =>
        val cookies = loginBy(host, user.email)
        request(host, s"/r/${b.persistedId}", cookies)._1 shouldBe 404

        testContext.grant(user, b)

        val (status, renewed) = request(host, s"/r/${b.persistedId}", cookies)
        status shouldBe 200
        renewed should not be None
        renewed.get.getValue should not be cookies(SessionController.AUTH_COOKIE_NAME).getValue
        request(host, s"/r/${b.persistedId}", Map(SessionController.AUTH_COOKIE_NAME -> renewed.get)) shouldBe (200, None)
    }
  }

  test("The Library gate reads the token, not the database") {

    /**
     * Setup:
     *
     * A User logged in while granted access to a Library; the grant is then deleted by hand-written SQL, which raises no version.
     *
     * Assertions:
     *
     * The Library's page still answers 200: the gate reads the grants in the token, and nothing told it of the change. Once the
     * account's version is raised, as every account write does after its commit, the page is a 404.
     */
    val user = testContext.persistUser()
    val library = addLibrary("Library A", user)

    withServer(App) {
      host =>
        val cookies = loginBy(host, user.email)
        testApp.txManager.withTransaction {
          testApp.DAO.user.updateByBySql("DELETE FROM repository_grant WHERE account_id = ?", List(user.persistedId))
        }

        request(host, s"/r/${library.persistedId}", cookies)._1 shouldBe 200

        testApp.service.accountVersions.bump(user.persistedId)
        request(host, s"/r/${library.persistedId}", cookies)._1 shouldBe 404
    }
  }

  test("A deactivated account's session, and a revoked one, is refused at its next request") {

    /**
     * Setup:
     *
     * Two logged-in Users holding a grant in a Library: the first is deactivated by hand-written SQL and its version raised, the
     * second's tokens are revoked, as a password change does.
     *
     * Assertions:
     *
     * Each session's next page request redirects to the login, and its next API request is a 401.
     */
    val deactivated = testContext.persistUser()
    val revoked = testContext.persistUser()
    val library = testContext.persistRepository()
    testContext.grant(deactivated, library)
    testContext.grant(revoked, library)

    withServer(App) {
      host =>
        val deactivatedCookies = loginBy(host, deactivated.email)
        val revokedCookies = loginBy(host, revoked.email)

        setActive(deactivated, active = false)
        testApp.service.accountVersions.bump(deactivated.persistedId)
        testApp.service.accountVersions.revoke(revoked.persistedId)

        for (cookies <- List(deactivatedCookies, revokedCookies)) {
          request(host, s"/r/${library.persistedId}", cookies)._1 shouldBe 302
          request(host, s"/api/folder/r/${library.persistedId}/tree", cookies, json = true)._1 shouldBe 401
        }
    }
  }

  test("A Bearer token opens its Library while current, and is refused once stale") {

    /**
     * Setup:
     *
     * A User logged in with a grant in a Library, its session token sent as a Bearer header; then the User is granted Import in a
     * second Library, which raises its version.
     *
     * Assertions:
     *
     * The folder tree API answers 200 to the current Bearer token and 401 to the stale one, which the server cannot replace.
     */
    val user = testContext.persistUser()
    val library = addLibrary("Library A", user)

    withServer(App) {
      host =>
        val token = loginBy(host, user.email)(SessionController.AUTH_COOKIE_NAME).getValue
        val tree = s"/api/folder/r/${library.persistedId}/tree"
        request(host, tree, bearer = Some(token), json = true)._1 shouldBe 200

        testContext.grant(user, testContext.persistRepository(), Permissions.of(Permission.Import))

        request(host, tree, bearer = Some(token), json = true)._1 shouldBe 401
    }
  }

  test("A refused session cookie is cleared by the answer that refuses it") {

    /**
     * Setup:
     *
     * A User holding a grant in a Library, and a session cookie that holds no token the server minted.
     *
     * Assertions:
     *
     * A page request with the cookie redirects to the login and an API request is a 401, each answer clearing the session cookie
     * (empty, Max-Age 0), so the browser stops sending it.
     *
     * Edge cases:
     *
     * A refused Bearer token, and a request with no token at all, are refused with no cookie set: there is no cookie to clear.
     */
    val user = testContext.persistUser()
    val library = addLibrary("Library A", user)
    val forged = Map(SessionController.AUTH_COOKIE_NAME -> HttpCookie(SessionController.AUTH_COOKIE_NAME, "v2.local.forged"))

    withServer(App) {
      host =>
        def sessionCookieSet(
            path: String,
            cookies: Map[String, HttpCookie],
            headers: Map[String, String] = Map.empty): (Int, Option[(String, Long)]) = {
          val response = requests.get(s"$host$path", cookies = cookies, headers = headers, maxRedirects = 0, check = false)
          val set = response.headers
            .getOrElse("set-cookie", Nil)
            .flatMap(header => HttpCookie.parse(header).asScala)
            .find(_.getName == SessionController.AUTH_COOKIE_NAME)
          // Undertow writes an empty value quoted
          (response.statusCode, set.map(cookie => (cookie.getValue.stripPrefix("\"").stripSuffix("\""), cookie.getMaxAge)))
        }

        sessionCookieSet(s"/r/${library.persistedId}", forged) shouldBe (302, Some(("", 0L)))
        sessionCookieSet(s"/api/folder/r/${library.persistedId}/tree", forged) shouldBe (401, Some(("", 0L)))
        sessionCookieSet(
          s"/api/folder/r/${library.persistedId}/tree",
          Map.empty,
          Map("Authorization" -> "Bearer v2.local.forged")) shouldBe (401, None)
        sessionCookieSet(s"/r/${library.persistedId}", Map.empty) shouldBe (302, None)
    }
  }
}
