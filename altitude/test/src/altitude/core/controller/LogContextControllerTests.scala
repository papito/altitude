package altitude.core.controller

import altitude.test.LoggedLine
import altitude.test.TestContext
import java.net.HttpCookie
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.slf4j.MDC

import altitude.core.App
import altitude.core.Const
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.routes.web.SessionController

/**
 * What a request's log lines carry in the MDC: its request ID, and the ID of its account once it has one. Every test serves `App`
 * on one request thread, so a request runs on the thread the request before it ran on, and reads the lines as they were logged
 * (`LoggedLine`).
 */
@DoNotDiscover class LogContextControllerTests extends ControllerTestCore {

  private val COOKIE = SessionController.AUTH_COOKIE_NAME

  /** `App` on one request thread */
  private def onOneThread[T](f: String => T): T = withServer(App, requestThreads = Some(1))(f)

  /** The lines the request thread logs for one request */
  private def linesOf(request: => requests.Response): Seq[LoggedLine] =
    LoggedLine.during(request).filter(_.onRequestThread)

  /** The one request ID a request's lines carry, from its START line to its END line */
  private def requestIdOf(lines: Seq[LoggedLine]): String = {
    lines.head.message should startWith("Request START")
    lines.last.message should startWith("Request END")
    val ids = lines.map(_.requestId).distinct
    ids should have size 1
    (ids.head.getOrElse(fail("A line with no request ID")) should fullyMatch).regex("[A-Za-z0-9]{6}")
    ids.head.get
  }

  /** The users a request's lines name: none, or the one account's ID */
  private def usersOf(lines: Seq[LoggedLine]): Seq[String] = lines.flatMap(_.user).distinct

  private def tree(
      host: String,
      repository: Repository,
      cookies: Map[String, HttpCookie] = Map.empty,
      bearer: Option[String] = None): requests.Response =
    requests.get(
      s"$host/api/folder/r/${repository.persistedId}/tree",
      cookies = cookies,
      headers = bearer.map(token => "Authorization" -> s"Bearer $token").toMap,
      check = false)

  private def loginPage(host: String): requests.Response = requests.get(s"$host/login", check = false)

  private def login(host: String, account: User, password: String = TestContext.USER_PASSWORD): requests.Response =
    requests.post(s"$host/login", maxRedirects = 0, check = false, data = Map("login" -> account.email, "password" -> password))

  /** A signed content URL whose signature does not verify; the request logger writes no START or END line under `/content` */
  private def forgedLink(host: String, repository: Repository): requests.Response =
    requests.get(
      s"$host/content/r/${repository.persistedId}/file/no-such-asset?exp=${Long.MaxValue}&sig=forged",
      maxRedirects = 0,
      check = false)

  private def forgedCookie: Map[String, HttpCookie] = Map(COOKIE -> new HttpCookie(COOKIE, "forged"))

  test("A signed-in request's log lines carry its account's ID and its request ID") {

    /**
     * Setup:
     *
     * A User with a grant in a Library, who logs in and then reads the Library's folder tree twice: with the session cookie, and
     * with the session token as a Bearer header.
     *
     * Assertions:
     *
     * Each request's lines, from START to END, carry one request ID of its own. The END line of each carries the account's ID as
     * the user, and no line carries any other user: the email address, the username and the name appear in no line's context.
     *
     * Edge cases:
     *
     * A request's START line carries no user: it is logged before the session is read. The login request itself carries the
     * account's ID from the moment the password is accepted. The dev login, which signs a request in with no session, puts the
     * account's ID in the context as a token does, and nothing when its password is wrong.
     */
    val account = testContext.persistUser()
    val repository = testContext.persistRepository()
    testContext.grant(account, repository)

    onOneThread {
      host =>
        var cookies = Map.empty[String, HttpCookie]
        val loggingIn = linesOf {
          val response = login(host, account)
          response.statusCode shouldBe 302
          cookies = response.cookies
          response
        }
        val withCookie = linesOf(tree(host, repository, cookies))
        val withBearer = linesOf(tree(host, repository, bearer = Some(cookies(COOKIE).getValue)))

        Seq(loggingIn, withCookie, withBearer).map(requestIdOf).distinct should have size 3

        Seq("logging in" -> loggingIn, "the cookie" -> withCookie, "the Bearer token" -> withBearer).foreach {
          (how, lines) =>
            withClue(s"[$how]: ") {
              lines.head.user shouldBe None
              lines.last.user shouldBe Some(account.persistedId)
              usersOf(lines) shouldBe Seq(account.persistedId)
            }
        }
        withCookie.last.message should startWith("Request END [200]")
        withBearer.last.message should startWith("Request END [200]")
    }

    try {
      MDC.clear()
      testApp.service.user.devUser((account.email, "not the password")) shouldBe None
      MDC.get(Const.LogContext.USER) shouldBe null

      testApp.service.user.devUser((account.email, TestContext.USER_PASSWORD)).map(_.account.persistedId) shouldBe
        Some(account.persistedId)
      MDC.get(Const.LogContext.USER) shouldBe account.persistedId
    } finally MDC.clear()
  }

  test("A request with no session, or with one that is refused, carries no user") {

    /**
     * Setup:
     *
     * A User with a grant in a Library, and requests that sign nobody in: the login page, the Library's folder tree with no
     * cookie and with a forged one, a login with a wrong password, and a signed content URL whose signature is forged.
     *
     * Assertions:
     *
     * No line of any of them carries a user, the refusal of the forged cookie and the failed login included. Each request but the
     * signed URL carries a request ID of its own.
     *
     * Edge cases:
     *
     * The signed URL's refusal carries no request ID either: the request logger gives none under `/content`, and no earlier
     * request's is left on the thread.
     */
    val account = testContext.persistUser()
    val repository = testContext.persistRepository()
    testContext.grant(account, repository)

    onOneThread {
      host =>
        val anonymous = Seq(
          "the login page" -> linesOf(loginPage(host)),
          "no cookie" -> linesOf(tree(host, repository)),
          "a forged cookie" -> linesOf(tree(host, repository, forgedCookie)),
          "a wrong password" -> linesOf(login(host, account, password = "not the password"))
        )

        anonymous.foreach((how, lines) => withClue(s"[$how]: ")(usersOf(lines) shouldBe empty))
        anonymous.map((_, lines) => requestIdOf(lines)).distinct should have size anonymous.size

        anonymous(1)._2.last.message should startWith("Request END [401]")
        anonymous(2)._2.map(_.message) should contain("Invalid, expired, revoked or stale token")
        anonymous(3)._2.last.message should startWith("Request END [401]")

        val refusedLink = linesOf(forgedLink(host, repository))
        refusedLink.map(_.message).exists(_.contains("the signature does not match")) shouldBe true
        usersOf(refusedLink) shouldBe empty
        refusedLink.flatMap(_.requestId) shouldBe empty
    }
  }

  test("Requests in a row on one thread never carry an earlier request's user or request ID") {

    /**
     * Setup:
     *
     * Two Users, each with a grant in the one Library, both logged in. Then, on the server's one request thread, in a row: the
     * first User's folder tree, the login page with no session, the second User's folder tree, a forged signed content URL, the
     * folder tree with a forged cookie, the first User's folder tree by Bearer token, a login with a wrong password, and the
     * second User's folder tree again.
     *
     * Assertions:
     *
     * Every request ran on the same thread. Each signed-in request's lines name its own account and no other; each request with
     * no session names no user, whoever came before it. No request carries the request ID of another.
     *
     * Edge cases:
     *
     * The forged signed URL, which gets no request ID of its own, carries none: not the ID of the request before it.
     */
    val first = testContext.persistUser()
    val second = testContext.persistUser()
    val repository = testContext.persistRepository()
    testContext.grant(first, repository)
    testContext.grant(second, repository)

    onOneThread {
      host =>
        val asFirst = cookiesOf(host, first)
        val asSecond = cookiesOf(host, second)

        // What it is, the account it signs in, whether the request logger gives it a request ID, and the request
        val inARow: Seq[(String, Option[User], Boolean, () => requests.Response)] = Seq(
          ("the first User", Some(first), true, () => tree(host, repository, asFirst)),
          ("the login page", None, true, () => loginPage(host)),
          ("the second User", Some(second), true, () => tree(host, repository, asSecond)),
          ("a forged signed URL", None, false, () => forgedLink(host, repository)),
          ("a forged cookie", None, true, () => tree(host, repository, forgedCookie)),
          (
            "the first User's Bearer token",
            Some(first),
            true,
            () => tree(host, repository, bearer = Some(asFirst(COOKIE).getValue))),
          ("a wrong password", None, true, () => login(host, first, password = "not the password")),
          ("the second User again", Some(second), true, () => tree(host, repository, asSecond))
        )

        val logged = inARow.map {
          (what, account, hasRequestId, request) =>
            val lines = linesOf(request())
            withClue(s"[$what]: ") {
              lines should not be empty
              usersOf(lines) shouldBe account.map(_.persistedId).toSeq
              if account.isDefined then lines.last.message should startWith("Request END [200]")
              if !hasRequestId then lines.flatMap(_.requestId) shouldBe empty
            }
            (lines, hasRequestId)
        }

        logged.flatMap((lines, _) => lines.map(_.thread)).distinct should have size 1

        val requestIds = logged.collect { case (lines, true) => requestIdOf(lines) }
        requestIds.distinct should have size inARow.count(_._3)
    }
  }
}
