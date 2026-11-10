package altitude.core.controller

import altitude.test.TestContext
import java.net.URLEncoder
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, include, should, shouldBe, startWith }

import altitude.core.Api
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

  test("Each page's browser tab title names its Library: alone on the main page, after the page's name elsewhere") {

    /**
     * Setup:
     *
     * A logged-in Admin (so the Settings page answers) with the Library "Smith & Sons".
     *
     * Assertions:
     *
     * The main page is titled "Smith & Sons · Altitude", in any view, the import page "Import · Smith & Sons · Altitude" and the
     * Settings page "Settings · Smith & Sons · Altitude", the name in each a first-strong isolate (U+2068 … U+2069), so a
     * right-to-left name neither sets the title's direction nor reorders the text beside it.
     *
     * Edge cases:
     *
     * The name is HTML-escaped in the title.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val repoId = addLibrary("Smith & Sons", admin).persistedId
    login()

    withServer(App) {
      host =>
        def title(path: String, params: Map[String, String] = Map.empty): String = {
          val response = requests.get(s"$host/$path", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          """<title>([^<]*)</title>""".r.findFirstMatchIn(response.text()).map(_.group(1)).getOrElse("")
        }

        // The name between FIRST STRONG ISOLATE and POP DIRECTIONAL ISOLATE
        val name = "\u2068Smith &amp; Sons\u2069"
        title(s"r/$repoId") shouldBe s"$name · Altitude"
        title(s"r/$repoId", Map(Api.Field.Search.VIEW -> Const.Search.View.TRIAGE)) shouldBe s"$name · Altitude"
        title(s"pipeline/r/$repoId") shouldBe s"Import · $name · Altitude"
        title(s"settings/r/$repoId") shouldBe s"Settings · $name · Altitude"
    }
  }

  test("A Library that does not exist or is another user's is a plain-text Library not found, and JSON for an API request") {

    /**
     * Setup:
     *
     * A logged-in user's Library, and another user's Library.
     *
     * Assertions:
     *
     * The main page of the other user's Library and of a Library ID that names none answer the same plain-text 404, "Library not
     * found"; an API request for either is the JSON 404 of a foreign entity.
     */
    testContext.persistRepository()
    login()
    val stranger = testApp.service.user.add(testContext.makeUser(), TestContext.USER_PASSWORD)
    val strangersRepo = addLibrary("Stranger's library", stranger)

    withServer(App) {
      host =>
        for (repoId <- List(strangersRepo.persistedId, java.util.UUID.randomUUID().toString)) {
          val page = requests.get(s"$host/r/$repoId", cookies = testContext.cookies, check = false)
          page.statusCode shouldBe 404
          page.contentType.head should startWith("text/plain")
          page.text() shouldBe Const.Msg.Err.LIBRARY_NOT_FOUND

          val api = requests.get(s"$host/api/album/r/$repoId/list", cookies = testContext.cookies, check = false)
          api.statusCode shouldBe 404
          api.contentType.head should startWith("application/json")
        }
    }
  }

  /** The Browse menu's host in `page`: from its opening tag to the end of the host, which holds no other `div` */
  private def browseMenu(page: String): String = {
    val start = page.indexOf("""data-choice-menu="Browse"""")
    start should be >= 0
    page.substring(start, page.indexOf("</div>", start))
  }

  /** The Libraries a Browse menu lists, in order: each one's link, its title and whether it is checked */
  private def browseMenuOptions(page: String): List[(String, String, Boolean)] =
    """<a href="([^"]+)" title="([^"]*)"( aria-checked="true")?>""".r
      .findAllMatchIn(browseMenu(page))
      .map(m => (m.group(1), m.group(2), m.group(3) != null))
      .toList

  test("The nav's Browse menu lists the account's Libraries by name, checks the current one, and offers an Admin New library") {

    /**
     * Setup:
     *
     * A logged-in Admin with Libraries "Client 10", "client 2" and "Alpha", and another user's Library "Beta".
     *
     * Assertions:
     *
     * On the main page of "client 2", its Import and Settings pages, the Browse cell holds the Browse choice menu (the database
     * icon), which lists the Admin's Libraries by name, case-insensitively and naturally (Alpha, client 2, Client 10), each a
     * link to its Library's main page titled with its name, with "client 2" alone checked; then a separator and "New library…",
     * which requests the New library dialog of "client 2" into the popover dialog host. The main page's Browse cell keeps
     * `aria-current="page"`.
     *
     * Edge cases:
     *
     * The other user's Library is not listed.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val client10 = addLibrary("Client 10", admin)
    val client2 = addLibrary("client 2", admin)
    val alpha = addLibrary("Alpha", admin)
    // Not one of the test context's users, whose one user logs in
    addLibrary("Beta", testApp.service.user.add(testContext.makeUser(), TestContext.USER_PASSWORD))
    login()
    val repoId = client2.persistedId

    withServer(App) {
      host =>
        def get(path: String): String = {
          val response = requests.get(s"$host/$path", cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }

        val mainPage = get(s"r/$repoId")
        mainPage should include("""<div class="menu repository" aria-current="page">""")

        for (page <- List(mainPage, get(s"pipeline/r/$repoId"), get(s"settings/r/$repoId"))) {
          val menu = browseMenu(page)
          menu should include("""data-choice-menu-icon="fas fa-database"""")
          browseMenuOptions(page) shouldBe List(
            (s"/r/${alpha.persistedId}", "Alpha", false),
            (s"/r/$repoId", "client 2", true),
            (s"/r/${client10.persistedId}", "Client 10", false))
          menu should include("<hr>")
          menu should include(s"""hx-get="/htmx/library/r/$repoId/dialogs/new"""")
          menu should include("""hx-target="#popoverContent"""")
          menu should include(s">${Const.UI.NEW_LIBRARY_OPTION}</button>")
          menu.contains("Beta") shouldBe false
        }
    }
  }

  test("A User's Browse menu lists their Library and offers no New library") {

    /**
     * Setup:
     *
     * A logged-in User's Library.
     *
     * Assertions:
     *
     * The main page lists the Library, checked, in the Browse menu, with no separator and no New library entry.
     */
    val repoId = loginAs(AccountType.User)
    val name = testContext.repository.name

    withServer(App) {
      host =>
        val page = requests.get(s"$host/r/$repoId", cookies = testContext.cookies, check = false).text()
        browseMenuOptions(page) shouldBe List((s"/r/$repoId", name, true))
        browseMenu(page).contains("<hr>") shouldBe false
        page.contains(Const.UI.NEW_LIBRARY_OPTION) shouldBe false
        page.contains("/dialogs/new") shouldBe false
    }
  }

  test("A nav reload renders the Browse menu's host empty, for the page's built one to take its place") {

    /**
     * Setup:
     *
     * A logged-in Admin with two Libraries.
     *
     * Assertions:
     *
     * A nav reload's Browse cell holds the Browse menu's host by its ID, `hx-preserve` and empty: no Library, no choice menu
     * attributes and no New library entry, since htmx keeps the page's built host in its place and drops the one the reload
     * carries.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val repoId = addLibrary("Library A", admin).persistedId
    addLibrary("Library B", admin)
    login()

    withServer(App) {
      host =>
        val response = requests.get(s"$host/htmx/nav/r/$repoId", cookies = testContext.cookies, check = false)
        response.statusCode shouldBe 200
        val nav = response.text()
        val emptyHost = """<div class="menu repository">\s*<div id="libraryMenuHost" hx-preserve></div>\s*</div>""".r
        emptyHost.findFirstIn(nav).isDefined shouldBe true
        nav.contains("data-choice-menu") shouldBe false
        nav.contains("Library A") shouldBe false
        nav.contains(Const.UI.NEW_LIBRARY_OPTION) shouldBe false
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

  test("A full page of a Library answers though recording it as the last active one fails") {

    /**
     * Setup:
     *
     * A logged-in user with Libraries A and B, B recorded as the last active one, and a trigger on the account table that refuses
     * every update, so recording A fails.
     *
     * Assertions:
     *
     * A's main page and its Import page answer 200, and B stays recorded.
     */
    val user = testContext.persistUser()
    val a = addLibrary("Library A", user)
    val b = addLibrary("Library B", user)
    testApp.service.user.rememberLastActiveRepository(user, b.persistedId)
    login()

    def ddl(sql: String): Unit = testApp.txManager.withTransaction(testApp.DAO.user.updateByBySql(sql, Nil): Unit)
    ddl("CREATE TRIGGER refuse_account_update BEFORE UPDATE ON account BEGIN SELECT RAISE(ABORT, 'refused'); END")

    try
      withServer(App) {
        host =>
          for (path <- List(s"r/${a.persistedId}", s"pipeline/r/${a.persistedId}")) {
            withClue(s"[$path]: ") {
              requests.get(s"$host/$path", cookies = testContext.cookies, check = false).statusCode shouldBe 200
            }
          }
      }
    finally ddl("DROP TRIGGER refuse_account_update")

    lastActiveRepoIdOf(user) shouldBe Some(b.persistedId)
  }
}
