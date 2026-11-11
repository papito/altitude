package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ be, include, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const

@DoNotDiscover class SetupFormControllerTests extends ControllerTestCore {

  /**
   * Runs `f` against the server's instance as a new installation. The server's instance keeps its own flag, put back afterwards
   * for the next suites; the database is reset before each test.
   */
  private def onNewInstallation[T](f: String => T): T = {
    val wasInitialized = App.altitude.isInitialized
    uninitializeInstance()
    App.altitude.isInitialized = false
    try withServer(App)(f)
    finally App.altitude.isInitialized = wasInitialized
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
     * Each refused username answers the setup form with the error under the Username field (required, or the rule) and creates
     * nothing. The valid form creates the Admin under the username "Chief.Admin", trimmed, and the Admin logs in with it in lower
     * case.
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
}
