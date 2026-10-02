package altitude.core.controller

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
    val repo = testContext.persistRepository()
    testApp.service.system.readMetadata.isInitialized shouldBe true

    withServer(App) {
      host =>
        val response = requests.get(s"$host/r/${repo.persistedId}", maxRedirects = 0, check = false)
        response.statusCode shouldBe 302
    }
  }

  test("The Search input is in the nav of the main page only, and a nav reload keeps it there") {
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
