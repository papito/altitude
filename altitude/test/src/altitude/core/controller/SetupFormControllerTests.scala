package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

import altitude.core.Api
import altitude.core.App

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

  private def postSetup(host: String, libraryName: String): requests.Response = requests.post(
    s"$host/htmx/setup",
    headers = Map("Content-Type" -> "application/json"),
    data = ujson.write(
      ujson.Obj(
        Api.Field.Setup.REPOSITORY_NAME -> libraryName,
        Api.Field.Setup.ADMIN_NAME -> "Administrator",
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
}
