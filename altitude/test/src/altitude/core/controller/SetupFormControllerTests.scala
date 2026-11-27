package altitude.core.controller

import altitude.test.LoggedLine
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ be, include, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.routes.decorators
import altitude.core.routes.web.SessionController
import altitude.core.util.Query

@DoNotDiscover class SetupFormControllerTests extends ControllerTestCore {

  /** Runs `f` against the server with its instance's in-memory flag set to `initialized`, putting it back afterwards */
  private def withServerFlag[T](initialized: Boolean)(f: String => T): T = {
    val wasInitialized = App.altitude.isInitialized
    App.altitude.isInitialized = initialized
    try withServer(App)(f)
    finally App.altitude.isInitialized = wasInitialized
  }

  /** Runs `f` with the server's instance as a new installation, putting its flag back afterwards for the next suites. */
  private def onNewInstallation[T](f: String => T): T = {
    uninitializeInstance()
    withServerFlag(initialized = false)(f)
  }

  private def postSetup(host: String, libraryName: String, username: String = "admin"): requests.Response = requests.post(
    s"$host/htmx/setup",
    headers = Map("Content-Type" -> "application/json"),
    data = ujson.write(
      ujson.Obj(
        Api.Field.Setup.REPOSITORY_NAME -> libraryName,
        Api.Field.Setup.ADMIN_NAME -> "Administrator",
        Api.Field.Setup.ADMIN_USERNAME -> username,
        Api.Field.Setup.ADMIN_EMAIL -> "admin@example.com",
        Api.Field.Setup.PASSWORD -> "password123",
        Api.Field.Setup.PASSWORD2 -> "password123"
      )),
    maxRedirects = 0,
    check = false
  )

  test("A Setup submission larger than a form is refused, on a new installation and after") {

    /**
     * Setup:
     *
     * A new installation, then an initialized instance. Each is posted, by nobody signed in, a Setup submission that would set
     * the instance up, with a field of padding that makes it three times the size a submission may have: once with its length
     * declared and once chunked. Then a request that declares ten megabytes and sends none of them.
     *
     * Assertions:
     *
     * A body that declares its length is answered 413, the last one without its body. The chunked one is read up to the limit,
     * then its connection is closed with no answer. The route is open to anyone, and reads no body whole into memory that is
     * larger than a form: nothing is set up by any of them.
     *
     * Edge cases:
     *
     * On an initialized instance the refusal comes before the answer that it is already initialized, which is given only once the
     * body is read.
     */
    val oversized = ujson
      .write(
        ujson.Obj(
          Api.Field.Setup.REPOSITORY_NAME -> "Oversized",
          Api.Field.Setup.ADMIN_NAME -> "Administrator",
          Api.Field.Setup.ADMIN_USERNAME -> "admin",
          Api.Field.Setup.ADMIN_EMAIL -> "admin@example.com",
          Api.Field.Setup.PASSWORD -> "password123",
          Api.Field.Setup.PASSWORD2 -> "password123",
          "padding" -> "A" * Math.toIntExact(decorators.SMALL_BODY_BYTES * 3)
        ))
      .getBytes

    def refusals(host: String): Unit = {
      rawPostStatus(host, "/htmx/setup", "application/json", oversized, Some(oversized.length.toLong)) shouldBe Some(413)
      rawPostStatus(host, "/htmx/setup", "application/json", oversized, declaredLength = None) shouldBe None
      rawPostStatus(host, "/htmx/setup", "application/json", Array.emptyByteArray, Some(10000000L)) shouldBe Some(413)
    }

    onNewInstallation {
      host =>
        refusals(host)
        testApp.service.system.readMetadata.isInitialized shouldBe false
    }
    withServer(App)(refusals)
  }

  test("Setup is refused once the database says the instance is set up, though the server's flag does not yet") {

    /**
     * Setup:
     *
     * An instance set up, as each controller test's is, whose server has its in-memory flag cleared, as a submission racing
     * another finds it before the other commits; then a valid setup form posted by nobody signed in.
     *
     * Assertions:
     *
     * The submission is answered 400 with the refusal of an instance already set up, as one the server knows is set up is, and
     * adds no account and no Library. The server's flag then says the instance is set up.
     */
    testApp.service.system.readMetadata.isInitialized shouldBe true
    val accounts = testApp.service.user.query(new Query()).records.size
    val libraries = testApp.service.repository.getAll.size

    withServerFlag(initialized = false) {
      host =>
        val response = postSetup(host, "Second Library", "second.admin")
        response.statusCode shouldBe 400
        response.text() shouldBe Const.Msg.Err.ALREADY_INITIALIZED
        // The refusal means a Setup has committed, so the server's flag now says so too
        App.altitude.isInitialized shouldBe true
    }

    testApp.service.user.query(new Query()).records.size shouldBe accounts
    testApp.service.repository.getAll.size shouldBe libraries
  }

  test("Setup refuses a Library name the Library name rule refuses, under the field") {

    /**
     * Setup:
     *
     * A new installation, and setup forms valid but for the Library name: one character once composed ("e" and a combining
     * accent), and one with a control character between its words.
     *
     * Assertions:
     *
     * Each submission answers with the setup form carrying the rule's error under the Library name, and the typed name kept;
     * nothing is created.
     */
    onNewInstallation {
      host =>
        val tooShort = postSetup(host, "e\u0301")
        tooShort.statusCode shouldBe 200
        tooShort.text() should include("""id="setupForm"""")
        tooShort.text() should include("Use at least 2 characters")
        tooShort.text() should include("value='e\u0301'")

        val withControl = postSetup(host, "Client\u0007Smith")
        withControl.text() should include("Remove the control characters")

        testApp.service.repository.getAll shouldBe List()
        testApp.service.system.readMetadata.isInitialized shouldBe false
    }
  }

  test("Setup stores the first Library's name normalized") {

    /**
     * Setup:
     *
     * A new installation, and a valid setup form whose Library name has spaces around it and a tab between its words.
     *
     * Assertions:
     *
     * Setup redirects to the new Library, which is stored with its name trimmed and the tab and spaces collapsed to one space.
     */
    onNewInstallation {
      host =>
        val response = postSetup(host, "  My \t  Library ")
        response.statusCode shouldBe 200
        response.headers.get("hx-redirect").map(_.head) shouldBe Some(s"/r/${testApp.service.repository.getAll.head.persistedId}")

        testApp.service.repository.getAll.map(_.name) shouldBe List("My Library")
    }
  }

  test("Setup takes the Admin's username under the username rule, and the Admin logs in with it") {

    /**
     * Setup:
     *
     * A new installation, and setup forms valid but for the username: none, two characters, and one with a space; then a valid
     * form whose username is "Chief.Admin" with spaces around it.
     *
     * Assertions:
     *
     * Each refused username answers the setup form with its error under the Username field and creates nothing. The valid form
     * creates the Admin under "Chief.Admin", trimmed, who then logs in with it in lower case.
     */
    onNewInstallation {
      host =>
        for (
          (username, error) <- List(
            "" -> Const.Msg.Err.VALUE_REQUIRED,
            "ab" -> Const.Msg.Err.USERNAME_RULE,
            "chief admin" -> Const.Msg.Err.USERNAME_RULE)
        ) withClue(s"[$username]: ") {
          val refused = postSetup(host, "My Library", username)
          refused.statusCode shouldBe 200
          refused.text() should include("""id="setupForm"""")
          refused.text() should include(s"""<div class="error">$error</div>""")
          testApp.service.system.readMetadata.isInitialized shouldBe false
        }

        val response = postSetup(host, "My Library", "  Chief.Admin ")
        response.headers.get("hx-redirect") should not be None
        testApp.service.repository.getAll.size shouldBe 1

        val login = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map("login" -> "chief.admin", "password" -> "password123"))
        login.statusCode shouldBe 302
        login.headers("location") shouldBe Seq(s"/r/${testApp.service.repository.getAll.head.persistedId}")
    }
  }

  test("The Setup request's log lines carry the new Admin's ID once the Admin exists") {

    /**
     * Setup:
     *
     * A new installation, and a valid setup form sent with no session.
     *
     * Assertions:
     *
     * Setup signs the new Admin in, and the request's last line carries the Admin's ID as its user, with the request ID its first
     * line carries. No line carries any other user.
     *
     * Edge cases:
     *
     * The request's first line, logged before the Admin exists, carries no user.
     */
    onNewInstallation {
      host =>
        var response: Option[requests.Response] = None
        val lines = LoggedLine.during { response = Some(postSetup(host, "My Library")) }.filter(_.onRequestThread)

        response.get.headers.get("hx-redirect") should not be None
        val token = response.get.cookies(SessionController.AUTH_COOKIE_NAME).getValue
        val admin = App.altitude.service.paseto.readToken(token).get.account.persistedId

        lines.head.message should include("Request START")
        lines.head.user shouldBe None
        lines.last.message should include("Request END [200]")
        lines.last.user shouldBe Some(admin)
        lines.flatMap(_.user).distinct shouldBe Seq(admin)
        lines.map(_.requestId).distinct shouldBe Seq(lines.head.requestId)
        lines.head.requestId should not be None
    }
  }
}
