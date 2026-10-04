package altitude.core.controller

import java.net.URLEncoder
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

import altitude.core.App

@DoNotDiscover class IndexControllerTests extends ControllerTestCore {

  /*
  FIXME: This does not pass within the context of other tests (but will pass on its own) - what state is being shared across tests that is causing this to fail?
  test("New installation goes to setup page", Focused) {
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

  test("The Search input is in the nav of the main page only, and a nav reload keeps it there") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The Search form is in the main page's nav but not the import page's, and a nav reload carries it only when asked with
     * search=true, as the main page asks.
     */
    val repoId = testContext.persistRepository().persistedId
    login()

    withServer(App) {
      host =>
        def get(path: String, params: Map[String, String] = Map.empty): String = {
          val response = requests.get(s"$host/$path", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }
        val searchForm = """<form id="searchForm" role="search""""

        get(s"r/$repoId") should include(searchForm)
        get(s"pipeline/r/$repoId").contains(searchForm) shouldBe false
        // The main page reloads its nav with the form, the import page's refresh without
        get(s"htmx/nav/r/$repoId", Map("search" -> "true")) should include(searchForm)
        get(s"htmx/nav/r/$repoId").contains(searchForm) shouldBe false
    }
  }
}
