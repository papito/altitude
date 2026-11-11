package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.Permissions

/**
 * The New library dialog: an Admin's dialog and create, the redirect to the new Library's Import page, the Library name rule's
 * errors under the field, and the refusals of a User, a Guest and an anonymous request.
 */
@DoNotDiscover class LibraryActionControllerTests extends ControllerTestCore {

  private def dialogUrl(host: String, repoId: String) = s"$host/htmx/library/r/$repoId/dialogs/new"

  private def create(host: String, repoId: String, name: String, headers: Map[String, String] = Map.empty): requests.Response =
    requests.post(
      s"$host/htmx/library/r/$repoId/new",
      headers = Map("Content-Type" -> "application/json") ++ headers,
      data = ujson.write(ujson.Obj(Api.Field.Library.NAME -> name)),
      cookies = testContext.cookies,
      maxRedirects = 0,
      check = false
    )

  /** A refused name: the dialog's form replaces the submitted one in place, with `error` under the field and `typed` in it */
  private def assertRefused(response: requests.Response, error: String, typed: String): Unit =
    withClue(s"[$typed]: ") {
      response.statusCode shouldBe 200
      response.headers("hx-retarget") shouldBe Seq("this")
      response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
      response.headers.get("hx-redirect") shouldBe None
      response.text() should include("""id="newLibrary"""")
      response.text() should include(s"""<div class="error">$error</div>""")
      response.text() should include(s"""value="$typed"""")
    }

  /** The names of every Library of the instance */
  private def allLibraryNames: List[String] = testApp.service.repository.getAll.map(_.name).sorted

  test("An Admin opens the New library dialog") {

    /**
     * Setup:
     *
     * An Admin, logged in to a Library.
     *
     * Assertions:
     *
     * The dialog answers 200 with an empty name field in a popover dialog form titled "New library", which posts to the create
     * route of the Library it was opened from, and holds Create and Cancel.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

    withServer(App) {
      host =>
        val response = requests.get(dialogUrl(host, repoId), cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="newLibrary"""")
        response.text() should include("""data-app-fragment="modal"""")
        response.text() should include("""data-app-modal-title="New library"""")
        response.text() should include(s"""hx-post="/htmx/library/r/$repoId/new"""")
        response.text() should include(s"""name="${Api.Field.Library.NAME}"""")
        response.text() should include("""value=""""")
        response.text() should include(">Create</button>")
        response.text() should include(">Cancel</button>")
    }
  }

  test("A User and a Guest are refused the dialog and the create, which creates nothing") {

    /**
     * Setup:
     *
     * A User, then a Guest, each logged in to a Library it holds a grant in.
     *
     * Assertions:
     *
     * Both the dialog and the create answer 403, and no Library is added.
     */
    for (accountType <- List(AccountType.User, AccountType.Guest)) {
      testContext = new TestContext(testApp)
      val repoId = loginAs(accountType, Permissions.none)
      val before = allLibraryNames

      withServer(App) {
        host =>
          withClue(s"[$accountType]: ") {
            requests.get(dialogUrl(host, repoId), cookies = testContext.cookies, check = false).statusCode shouldBe 403
            create(host, repoId, "Client Smith").statusCode shouldBe 403
            allLibraryNames shouldBe before
          }
      }
    }
  }

  test("Without a login the create is a 401 and creates nothing") {

    /**
     * Setup:
     *
     * A Library, and no login.
     *
     * Assertions:
     *
     * The create, asked as a client asking for JSON, answers 401, and no Library is added.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val before = allLibraryNames

    withServer(App) {
      host =>
        create(host, repoId, "Client Smith", headers = Map("Accept" -> "application/json")).statusCode shouldBe 401
        allLibraryNames shouldBe before
    }
  }

  test("Create adds a Library the Admin can see and sends the browser to its Import page, which records it") {

    /**
     * Setup:
     *
     * An Admin, logged in, and a Library the login recorded as the last active one; the Admin creates a Library named with spaces
     * around it and a tab between its words.
     *
     * Assertions:
     *
     * The create answers with an `HX-Redirect` to the new Library's Import page. The new Library is among the Admin's, stored
     * with the name normalized. The last active Library is still the one the dialog was opened from, until the Import page of the
     * new one loads and records it.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val admin = testContext.user
    lastActiveRepoIdOf(admin) shouldBe Some(repoId)

    withServer(App) {
      host =>
        val response = create(host, repoId, "  Client \t Smith ")
        response.statusCode shouldBe 200

        val created = testApp.service.repository.getAccessibleTo(admin).find(_.persistedId != repoId).get
        created.name shouldBe "Client Smith"
        response.headers.get("hx-redirect").map(_.head) shouldBe Some(s"/pipeline/r/${created.persistedId}")

        lastActiveRepoIdOf(admin) shouldBe Some(repoId)
        requests
          .get(s"$host/pipeline/r/${created.persistedId}", cookies = testContext.cookies, check = false)
          .statusCode shouldBe 200
        lastActiveRepoIdOf(admin) shouldBe Some(created.persistedId)
    }
  }

  test("A name the Library name rule refuses re-renders the form with its error and the typed name") {

    /**
     * Setup:
     *
     * An Admin, logged in to a Library, who submits an empty name, a one-character name, an 81-character name, a name with a
     * control character between its words and one with a control character at its end.
     *
     * Assertions:
     *
     * Each submission answers with the dialog's form in place of the submitted one, the rule's error under the field and the
     * typed name kept in it; no Library is added.
     *
     * Edge cases:
     *
     * The control character at the end is refused, not trimmed away.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val before = allLibraryNames
    val tooLong = "x" * 81

    withServer(App) {
      host =>
        assertRefused(create(host, repoId, ""), "Enter a name for the library", "")
        assertRefused(create(host, repoId, "C"), "Use at least 2 characters", "C")
        assertRefused(create(host, repoId, tooLong), "Use at most 80 characters", tooLong)
        assertRefused(create(host, repoId, "Client\u0007Smith"), "Remove the control characters", "Client\u0007Smith")
        assertRefused(create(host, repoId, "Client Smith\u0007"), "Remove the control characters", "Client Smith\u0007")
        allLibraryNames shouldBe before
    }
  }

  test("A name another Library holds re-renders the form with the duplicate error and the typed name") {

    /**
     * Setup:
     *
     * An Admin, logged in, beside the Libraries "Client Smith" and "Studio Jones".
     *
     * Assertions:
     *
     * Each submission answers with the dialog's form in place of the submitted one, saying a library by the normalized name
     * already exists, with the typed name kept in the field; no Library is added.
     *
     * Edge cases:
     *
     * The name differs from the held one only in case or only in spacing, and either held name is taken.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    addLibrary("Client Smith", testContext.user)
    addLibrary("Studio Jones", testContext.user)
    val before = allLibraryNames

    withServer(App) {
      host =>
        assertRefused(create(host, repoId, "client smith"), "A library named “client smith” already exists", "client smith")
        assertRefused(create(host, repoId, "Client   Smith"), "A library named “Client Smith” already exists", "Client   Smith")
        assertRefused(create(host, repoId, "STUDIO JONES"), "A library named “STUDIO JONES” already exists", "STUDIO JONES")
        allLibraryNames shouldBe before
    }
  }
}
