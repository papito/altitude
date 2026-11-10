package altitude.core.controller

import altitude.test.TestContext
import java.net.URLEncoder
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, include, should, shouldBe }

import altitude.core.App
import altitude.core.Const
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

  test("An asset's page is the main page, with or without the Detail panel in its URL") {

    /**
     * Setup:
     *
     * A logged-in user's repository holding an image.
     *
     * Assertions:
     *
     * The asset's URL answers with the main page: the Asset detail host the client opens the asset in and the nav with Browse
     * marked, with no parameter and with the Detail panel's.
     */
    val repoId = testContext.persistRepository().persistedId
    login()

    withServer(App) {
      host =>
        val asset = testContext.persistAsset()

        def page(params: Map[String, String]): String = {
          val response = requests.get(
            s"$host/r/$repoId/asset/${asset.persistedId}",
            params = params,
            cookies = testContext.cookies,
            check = false)
          response.statusCode shouldBe 200
          response.text()
        }

        for (params <- Seq(Map.empty[String, String], Map("panel" -> "open"))) {
          val text = page(params)
          text should include("""id="imageDetailModalContent"""")
          text should include("""class="menu repository" aria-current="page"""")
        }
    }
  }

  test("An asset's page opens for an asset in the trash") {

    /**
     * Setup:
     *
     * A logged-in user's repository holding a recycled image, which Asset detail opens from the trash view.
     *
     * Assertions:
     *
     * The asset's URL answers with the main page, so reloading a detail opened from the trash does not lose it.
     */
    val repoId = testContext.persistRepository().persistedId
    login()

    withServer(App) {
      host =>
        val asset = testContext.persistAsset()
        testApp.service.library.recycleAssets(Set(asset.persistedId))

        val response = requests.get(s"$host/r/$repoId/asset/${asset.persistedId}", cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="imageDetailModalContent"""")
    }
  }

  test("An asset's page is not found for an unknown asset or another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs holding an image.
     *
     * Assertions:
     *
     * The page asked for through the first repository is a 404 for an ID that names no asset, and for the other repository's
     * asset too, which it does not name.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testContext.persistAsset(repository = Some(otherRepo))
        testApp.service.repository.switchContextToRepository(repo)

        def status(assetId: String): Int =
          requests.get(s"$host/r/$repoId/asset/$assetId", cookies = testContext.cookies, check = false).statusCode

        status(java.util.UUID.randomUUID().toString) shouldBe 404
        status(foreign.persistedId) shouldBe 404
    }
  }

  test("An asset's page sends a client that has not logged in to the login page and back") {

    /**
     * Setup:
     *
     * An initialized instance with a user's repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The asset's URL redirects to the login page, carrying the whole address to return to, the Detail panel's parameter
     * included.
     */
    val repoId = testContext.persistRepository().persistedId

    withServer(App) {
      host =>
        val assetPath = s"/r/$repoId/asset/${java.util.UUID.randomUUID()}?panel=open"
        val response = requests.get(s"$host$assetPath", maxRedirects = 0, check = false)

        response.statusCode shouldBe 302
        response.headers("location") shouldBe Seq(s"/login?redirect=${URLEncoder.encode(assetPath, "UTF-8")}")
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

  /** Where `/` sends the logged-in client */
  private def landing(host: String): Seq[String] =
    requests.get(s"$host/", cookies = testContext.cookies, maxRedirects = 0, check = false).headers("location")

  test("`/` opens the last active Library, and after a page load of another, that one, with the same login") {

    /**
     * Setup:
     *
     * A logged-in user with Libraries A, B and C, B recorded as the last active one before the login.
     *
     * Assertions:
     *
     * `/` redirects to B. Once C's main page has loaded, `/` redirects to C, though the login's token was issued before the
     * switch.
     */
    val user = testContext.persistUser()
    addLibrary("Library A", user)
    val b = addLibrary("Library B", user)
    val c = addLibrary("Library C", user)
    testApp.service.user.rememberLastActiveRepository(user, b.persistedId)
    login()

    withServer(App) {
      host =>
        landing(host) shouldBe Seq(s"/r/${b.persistedId}")
        requests.get(s"$host/r/${c.persistedId}", cookies = testContext.cookies).statusCode shouldBe 200
        landing(host) shouldBe Seq(s"/r/${c.persistedId}")
    }
  }

  test("`/` falls back to the first owned Library by name when the last active one is another user's or gone, and records it") {

    /**
     * Setup:
     *
     * A logged-in user's Libraries "Client 10" and "Client 2", the second added last, and another user's Library.
     *
     * Assertions:
     *
     * With the other user's Library recorded as the last active one, and then with an ID that names no Library, `/` redirects to
     * "Client 2", which is recorded as the last active one.
     */
    val user = testContext.persistUser()
    addLibrary("Client 10", user)
    val firstByName = addLibrary("Client 2", user)
    // Not one of the test context's users, whose one user logs in
    val foreign = addLibrary("Client 1", testApp.service.user.add(testContext.makeUser(), TestContext.USER_PASSWORD))
    login()

    withServer(App) {
      host =>
        for (lastActiveId <- List(foreign.persistedId, java.util.UUID.randomUUID().toString)) {
          withClue(s"[$lastActiveId]: ") {
            testApp.service.user.rememberLastActiveRepository(user, lastActiveId)
            landing(host) shouldBe Seq(s"/r/${firstByName.persistedId}")
            lastActiveRepoIdOf(user) shouldBe Some(firstByName.persistedId)
          }
        }
    }
  }

  test("A login and `/` of a user who owns no Library end in a plain not found, not a server error") {

    /**
     * Setup:
     *
     * A User who owns no Library.
     *
     * Assertions:
     *
     * The login redirects to `/`, which answers 404 in plain text saying there is no library to open.
     */
    testContext.persistUser()

    withServer(App) {
      host =>
        val loginResponse = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map("login" -> testContext.user.email, "password" -> TestContext.USER_PASSWORD))
        loginResponse.statusCode shouldBe 302
        loginResponse.headers("location") shouldBe Seq("/")

        val response = requests.get(s"$host/", cookies = loginResponse.cookies, maxRedirects = 0, check = false)
        response.statusCode shouldBe 404
        response.text() shouldBe Const.Msg.Err.NO_LIBRARY
    }
  }

  test("Each full page of a Library records it as the last active one; a reload, a partial and an API call write nothing") {

    /**
     * Setup:
     *
     * A logged-in Admin (so the Settings page answers) with Libraries A and B, an image in A.
     *
     * Assertions:
     *
     * A's main page, its Triage view, the image's page, Import and Settings each answer and record A when B is recorded before
     * the load, and a reload of each writes nothing.
     *
     * Edge cases:
     *
     * With B recorded, A's nav reload, its folder tree API and the page of an asset A does not hold leave B recorded and write
     * nothing.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val a = addLibrary("Library A", admin)
    val b = addLibrary("Library B", admin)
    login()

    withServer(App) {
      host =>
        testApp.service.repository.switchContextToRepository(a)
        val asset = testContext.persistAsset(repository = Some(a))
        val aId = a.persistedId

        for (
          path <- List(
            s"r/$aId",
            s"r/$aId?view=triage",
            s"r/$aId/asset/${asset.persistedId}",
            s"pipeline/r/$aId",
            s"settings/r/$aId")
        ) {
          assertPageRecords(host, admin, path, a, b)
        }

        testApp.service.user.rememberLastActiveRepository(admin, b.persistedId)
        clearAccountUpdatedAt(admin)
        val unrecorded =
          List(s"htmx/nav/r/$aId" -> 200, s"api/folder/r/$aId/tree" -> 200, s"r/$aId/asset/${java.util.UUID.randomUUID()}" -> 404)
        for ((path, status) <- unrecorded) {
          requests.get(s"$host/$path", cookies = testContext.cookies, check = false).statusCode shouldBe status
        }
        accountWritten(admin) shouldBe false
        lastActiveRepoIdOf(admin) shouldBe Some(b.persistedId)
    }
  }
}
