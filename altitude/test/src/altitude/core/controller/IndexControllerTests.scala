package altitude.core.controller

import java.net.URLEncoder
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, include, should, shouldBe }

import altitude.core.App
import altitude.core.models.AccountType

@DoNotDiscover class IndexControllerTests extends ControllerTestCore {

  /*
  FIXME: This does not pass within the context of other tests (but will pass on its own) - what state is being shared across tests that is causing this to fail?
  test("New installation goes to setup page") {
    uninitializeInstance()

    withServer(App) { host =>
      uninitializeInstance()
      testApp.service.system.readMetadata.isInitialized shouldBe false
      val response = requests.get(s"$host/")
      response.url should endWith("/setup")
    }
  }
   */

  test("Unauthenticated initialized install is not allowed to access protected route") {

    /**
     * Setup:
     *
     * An initialized instance with a user's repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The repository's main page answers with a redirect to the login page, which carries the page's address to return to.
     */
    val repo = testContext.persistRepository()
    testApp.service.system.readMetadata.isInitialized shouldBe true

    withServer(App) {
      host =>
        val response = requests.get(s"$host/r/${repo.persistedId}", maxRedirects = 0, check = false)
        response.statusCode shouldBe 302
        response.headers("location") shouldBe Seq(s"/login?redirect=${URLEncoder.encode(s"/r/${repo.persistedId}", "UTF-8")}")
    }
  }

  test("The Search input is in the nav of every page, and a nav reload keeps it there") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The Search form is in the main page's nav and the import page's, and a nav reload carries it too.
     */
    val repoId = testContext.persistRepository().persistedId
    login()

    withServer(App) {
      host =>
        def get(path: String): String = {
          val response = requests.get(s"$host/$path", cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }
        val searchForm = """<form id="searchForm" role="search""""

        get(s"r/$repoId") should include(searchForm)
        get(s"pipeline/r/$repoId") should include(searchForm)
        get(s"htmx/nav/r/$repoId") should include(searchForm)
    }
  }

  test("The nav marks the entry of the page shown, and a nav reload marks the entry it is asked for") {

    /**
     * Setup:
     *
     * A logged-in Admin's repository (an Admin, so the Settings page answers).
     *
     * Assertions:
     *
     * The main page marks Browse, or the view its URL asks for; the import and settings pages mark their own entries; a nav
     * reload marks the entry passed as `current`, and no entry without one.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        def get(path: String, params: Map[String, String] = Map.empty): String = {
          val response = requests.get(s"$host/$path", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }
        // The entry class of the one element carrying aria-current (an empty Triage or Trash entry can carry it too)
        val currentEntry = """class="menu ([a-z-]+)(?: empty)?" aria-current="page"""".r
        def current(page: String): Option[String] = {
          val marked = currentEntry.findAllMatchIn(page).map(_.group(1)).toList
          marked.size should be <= 1
          marked.headOption
        }

        current(get(s"r/$repoId")) shouldBe Some("repository")
        current(get(s"r/$repoId", Map("view" -> "triage"))) shouldBe Some("triage")
        current(get(s"pipeline/r/$repoId")) shouldBe Some("import")
        current(get(s"settings/r/$repoId")) shouldBe Some("settings")
        current(get(s"htmx/nav/r/$repoId", Map("current" -> "trashbin"))) shouldBe Some("trashbin")
        current(get(s"htmx/nav/r/$repoId")) shouldBe None
    }
  }
}
