package altitude.core.controller

import altitude.test.TestContext
import java.net.HttpCookie
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ be, contain, should, shouldBe }

import scala.jdk.CollectionConverters.*

import altitude.core.App
import altitude.core.Const as C
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.User
import altitude.core.routes.decorators
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

  test("A login's session cookie is Secure, as the server's config says by default") {

    /**
     * Setup:
     *
     * The test server, whose config leaves `server.secure_cookies` at its default, and a User with a repository to land in.
     *
     * Assertions:
     *
     * The server's instance reads the flag as true, and the `Set-Cookie` header of a login carries `Secure` beside `HttpOnly` and
     * `SameSite=Strict`.
     *
     * Edge cases:
     *
     * The cookie a logout clears the session with is `Secure` too.
     */
    testContext.persistRepository()
    testContext.grant(testContext.user, testContext.repository)

    App.altitude.secureCookies shouldBe true

    withServer(App) {
      host =>
        def sessionCookieOf(response: requests.Response): String =
          response.headers("set-cookie").find(_.startsWith(s"${SessionController.AUTH_COOKIE_NAME}=")).get

        val login = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map("login" -> testContext.user.email, "password" -> TestContext.USER_PASSWORD))
        login.statusCode shouldBe 302

        val attributes = sessionCookieOf(login).split("; ").toSet
        attributes should contain("Secure")
        attributes should contain("HttpOnly")
        attributes should contain("SameSite=Strict")

        val logout = requests.post(s"$host/logout", maxRedirects = 0, check = false, cookies = login.cookies)
        sessionCookieOf(logout).split("; ").toSet should contain("Secure")
    }
  }

  test("A login whose body is no form is a bare 400, and a form still logs in") {

    /**
     * Setup:
     *
     * An initialized instance with a User who can see a repository. The login route is posted a request with no body and no
     * content type, a body with no content type, a JSON body, a multipart body with no boundary and a multipart body that is not
     * multipart; then the User's login as a URL-encoded form and as a multipart form. The same posts go to the server as dev runs
     * it, in cask's debug mode.
     *
     * Assertions:
     *
     * Each body that is no form is answered 400 with the status line alone as its body, as any request the server cannot bind is:
     * no exception's name, no message, no stack frame. Both forms log the User in, with the redirect to the repository and the
     * session cookie.
     *
     * Edge cases:
     *
     * Debug mode changes none of it: the bare 400 does not depend on the environment. A form that parses but lacks the login's
     * fields is left to cask's own answer, bare outside dev.
     */
    testContext.persistRepository()
    testContext.grant(testContext.user, testContext.repository)
    val credentials = Map("login" -> testContext.user.email, "password" -> TestContext.USER_PASSWORD)

    def notForms(host: String): Seq[(String, requests.Response)] = {
      def post(data: String, contentType: Option[String]) =
        requests.post(s"$host/login", data = data, headers = contentType.map("Content-Type" -> _).toMap, check = false)

      Seq(
        "no body and no content type" -> requests.post(s"$host/login", check = false),
        "a body and no content type" -> post("login=someone&password=something", None),
        "JSON" -> post(ujson.write(ujson.Obj.from(credentials.view.mapValues(ujson.Str(_)))), Some("application/json")),
        "multipart with no boundary" -> post("login=someone", Some("multipart/form-data")),
        "multipart that is not" -> post("not a part", Some("multipart/form-data; boundary=frontier"))
      )
    }

    def assertLogsIn(response: requests.Response): Unit = {
      response.statusCode shouldBe 302
      response.headers("location").head shouldBe s"/r/${testContext.repository.persistedId}"
      response.cookies.contains(SessionController.AUTH_COOKIE_NAME) shouldBe true
    }

    for (app <- Seq(App, DevApp))
      withServer(app) {
        host =>
          for ((what, response) <- notForms(host))
            withClue(s"[${app.getClass.getSimpleName} $what]: ") {
              response.statusCode shouldBe 400
              response.text() shouldBe "Error 400: Bad Request"
            }

          assertLogsIn(requests.post(s"$host/login", data = credentials, maxRedirects = 0, check = false))
          assertLogsIn(
            requests.post(
              s"$host/login",
              data = requests.MultiPart(credentials.toSeq.map((name, value) => requests.MultiItem(name, value))*),
              maxRedirects = 0,
              check = false))
      }

    DevApp.debugMode shouldBe true
    withServer(App) {
      host =>
        val fieldless = requests.post(s"$host/login", data = Map("unknown" -> "field"), check = false)
        fieldless.statusCode shouldBe 400
        fieldless.text() shouldBe "Error 400: Bad Request"
    }
  }

  test("A login whose body is larger than a form's is refused, unread when it declares its length and cut off when chunked") {

    /**
     * Setup:
     *
     * An initialized instance with a User who can see a repository. The login route is posted, by nobody signed in, the User's
     * own login with a field of padding that makes the body three times the size a form may have: as a URL-encoded form and as a
     * multipart form, each with its length declared and each chunked. Then a request that declares ten megabytes and sends no
     * byte of them, and the User's login with a redirect of some thousand characters.
     *
     * Assertions:
     *
     * A body that declares its length is answered 413, and the one that only declares it is answered without its body: the
     * refusal reads none of it. A chunked body has no length to judge, so the server reads it up to the limit and then closes the
     * connection with no answer. None of them logs the User in, as each would if the server read it whole: a body is never read
     * into memory beyond the limit, so nobody who can reach the port can exhaust the heap with one. The long login is within the
     * limit and logs the User in.
     */
    testContext.persistRepository()
    testContext.grant(testContext.user, testContext.repository)

    val padding = "A" * Math.toIntExact(decorators.SMALL_BODY_BYTES * 3)
    val fields = Seq("login" -> testContext.user.email, "password" -> TestContext.USER_PASSWORD, "padding" -> padding)
    val urlEncoded = fields.map((name, value) => s"$name=${java.net.URLEncoder.encode(value, "UTF-8")}").mkString("&").getBytes
    val boundary = "frontier"
    val multipart = (fields
      .map((name, value) => s"--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
      .mkString + s"--$boundary--\r\n").getBytes

    withServer(App) {
      host =>
        val forms = Seq(
          "URL-encoded" -> ("application/x-www-form-urlencoded", urlEncoded),
          "multipart" -> (s"multipart/form-data; boundary=$boundary", multipart))
        for ((form, (contentType, body)) <- forms) {
          withClue(s"[$form, its length declared]: ") {
            rawPostStatus(host, "/login", contentType, body, Some(body.length.toLong)) shouldBe Some(413)
          }
          withClue(s"[$form, chunked]: ") {
            rawPostStatus(host, "/login", contentType, body, declaredLength = None) shouldBe None
          }
        }

        val unsent = rawPostStatus(host, "/login", "application/x-www-form-urlencoded", Array.emptyByteArray, Some(10000000L))
        unsent shouldBe Some(413)

        val long = requests.post(
          s"$host/login",
          data = Map(
            "login" -> testContext.user.email,
            "password" -> TestContext.USER_PASSWORD,
            "redirect" -> s"/r/${testContext.repository.persistedId}?text=${"a" * 4000}"),
          maxRedirects = 0,
          check = false
        )
        long.statusCode shouldBe 302
        long.cookies.contains(SessionController.AUTH_COOKIE_NAME) shouldBe true
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

  test("The login page is the app's, and a failed login says so and keeps the login typed, never the password") {

    /**
     * Setup:
     *
     * The login page, then a login with a wrong password sent with a page to come back to.
     *
     * Assertions:
     *
     * The page links the app's stylesheet, is titled, carries the brand and a "Log in" button, and shows no error. The failed
     * login answers 401 with the page showing the generic error, the login as typed and the page to come back to, with no trace
     * of the password.
     */
    val user = testContext.persistUser()

    withServer(App) {
      host =>
        val page = requests.get(s"$host/login").text()
        page should include(s"""href="${altitude.core.Assets.appCss}"""")
        page should include("<title>Log in · Altitude</title>")
        page should include("""<body class="login">""")
        page should include("""<div class="brand">""")
        page should include("""<button type="submit" class="action-button primary">Log in</button>""")
        (page should not).include(C.Msg.Err.LOGIN_FAILED)

        val password = s"wrong${Util.randomStr(8)}"
        val failed = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map("login" -> user.username, "password" -> password, "redirect" -> "/settings"))
        failed.statusCode shouldBe 401
        failed.text() should include(s"""<div class="error" role="alert">${C.Msg.Err.LOGIN_FAILED}</div>""")
        failed.text() should include(s"""value="${user.username}"""")
        failed.text() should include("""name="redirect" value="/settings"""")
        (failed.text() should not).include(password)
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
     * The Library's page still answers 200, since the gate reads the grants in the token. Once the account's version is raised,
     * as every account write does after its commit, the page is a 404.
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
     * (empty, Max-Age 0).
     *
     * Edge cases:
     *
     * A refused Bearer token and a request with no token at all set no cookie, since there is none to clear.
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

  /** The session cookie an answer sets, as its value (quotes off) and Max-Age, if any */
  private def sessionCookieOf(response: requests.Response): Option[(String, Long)] =
    response.headers
      .getOrElse("set-cookie", Nil)
      .flatMap(header => HttpCookie.parse(header).asScala)
      .find(_.getName == SessionController.AUTH_COOKIE_NAME)
      .map(cookie => (cookie.getValue.stripPrefix("\"").stripSuffix("\""), cookie.getMaxAge))

  test("An htmx request with no valid session is a 401 that sends the page it came from to the login") {

    /**
     * Setup:
     *
     * A User holding a grant in a Library, and htmx requests for a nav partial sent from the Library's Import page (with a
     * query): one with a forged session cookie, one with no cookie, and one with no `HX-Current-URL`.
     *
     * Assertions:
     *
     * Each is a 401, not a redirect htmx would follow and swap in, with `HX-Redirect` to the login carrying the page's own path
     * and query (not the partial's). The forged cookie's answer clears the cookie; the answer with no cookie sets none.
     *
     * Edge cases:
     *
     * Without `HX-Current-URL`, `HX-Redirect` is the login alone.
     */
    val user = testContext.persistUser()
    val library = addLibrary("Library A", user)
    val forged = Map(SessionController.AUTH_COOKIE_NAME -> HttpCookie(SessionController.AUTH_COOKIE_NAME, "v2.local.forged"))

    withServer(App) {
      host =>
        val page = s"/pipeline/r/${library.persistedId}?tab=1"
        def htmx(cookies: Map[String, HttpCookie], from: Option[String]) =
          requests.get(
            s"$host/htmx/nav/r/${library.persistedId}?current=import",
            cookies = cookies,
            headers = Map("HX-Request" -> "true") ++ from.map("HX-Current-URL" -> _),
            maxRedirects = 0,
            check = false
          )

        val toLogin = s"/login?redirect=${java.net.URLEncoder.encode(page, "UTF-8")}"
        val refused = htmx(forged, Some(s"$host$page"))
        refused.statusCode shouldBe 401
        refused.headers.get("hx-redirect") shouldBe Some(Seq(toLogin))
        sessionCookieOf(refused) shouldBe Some(("", 0L))

        val anonymous = htmx(Map.empty, Some(s"$host$page"))
        (anonymous.statusCode, anonymous.headers.get("hx-redirect")) shouldBe (401, Some(Seq(toLogin)))
        sessionCookieOf(anonymous) shouldBe None

        htmx(Map.empty, None).headers.get("hx-redirect") shouldBe Some(Seq("/login"))
    }
  }

  test("A session refused for a revoked token keeps its cookie; one of a deactivated account has it cleared") {

    /**
     * Setup:
     *
     * Two logged-in Users holding a grant in a Library, both of whose tokens are revoked, as a password change does; the second
     * is deactivated as well, by hand-written SQL.
     *
     * Assertions:
     *
     * Each session's next page request redirects to the login. The first's answer sets no cookie, so a token its browser got
     * meanwhile (the new one of a password change) is not cleared. The second's clears the session cookie.
     */
    val revoked = testContext.persistUser()
    val deactivated = testContext.persistUser()
    val library = testContext.persistRepository()
    testContext.grant(revoked, library)
    testContext.grant(deactivated, library)

    withServer(App) {
      host =>
        val revokedCookies = loginBy(host, revoked.email)
        val deactivatedCookies = loginBy(host, deactivated.email)
        testApp.service.accountVersions.revoke(revoked.persistedId)
        setActive(deactivated, active = false)
        testApp.service.accountVersions.revoke(deactivated.persistedId)

        def page(cookies: Map[String, HttpCookie]) =
          requests.get(s"$host/r/${library.persistedId}", cookies = cookies, maxRedirects = 0, check = false)
        val keeps = page(revokedCookies)
        (keeps.statusCode, sessionCookieOf(keeps)) shouldBe (302, None)
        val clears = page(deactivatedCookies)
        (clears.statusCode, sessionCookieOf(clears)) shouldBe (302, Some(("", 0L)))
    }
  }
}
