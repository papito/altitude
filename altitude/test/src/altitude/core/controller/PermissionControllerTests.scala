package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const as C
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions

/**
 * Permission checks a route's decorators cannot make, since they depend on the asset or on a parameter, and the controls a page
 * renders only for a holder. Which account may call which route at all is `RouteTableTests`'.
 */
@DoNotDiscover class PermissionControllerTests extends ControllerTestCore {

  private def jsonBody(fields: (String, ujson.Value)*): String = ujson.write(ujson.Obj.from(fields))

  private def sendJson(method: String, url: String, body: String): requests.Response =
    requests.send(method)(
      url,
      data = body,
      headers = Map("Content-Type" -> "application/json"),
      cookies = testContext.cookies,
      check = false)

  test("An asset the account's Permissions do not reach is a 403, in JSON on the API and in plain text elsewhere") {

    /**
     * Setup:
     *
     * A User holding Recycle and Organize but no Curate Permission, logged in to a Library with a triaged asset, a recycled
     * triaged asset, and a folder holding a sorted asset.
     *
     * Assertions:
     *
     * Recycling the triaged asset or restoring the recycled one is a JSON 403 saying how many assets the account cannot recycle
     * or restore, and nothing changes.
     *
     * Edge cases:
     *
     * Without Recycle, deleting a folder that holds an asset is a plain-text 403, and the folder stays.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Recycle, Permission.Organize))
    val triaged = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    val recycled = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    withServer(App) {
      host =>
        val recycle =
          sendJson("DELETE", s"$host/api/asset/r/$repoId/move", jsonBody(Api.Field.ASSET_IDS -> ujson.Arr(triaged.persistedId)))
        recycle.statusCode shouldBe 403
        ujson.read(recycle.text())("error").str should include("recycle 1 of these assets")
        (testApp.service.asset.getById(triaged.persistedId): Asset).isRecycled shouldBe false

        val restore =
          sendJson("PUT", s"$host/api/asset/r/$repoId/restore", jsonBody(Api.Field.ASSET_IDS -> ujson.Arr(recycled.persistedId)))
        restore.statusCode shouldBe 403
        ujson.read(restore.text())("error").str should include("restore 1 of these assets")
        (testApp.service.asset.getById(recycled.persistedId): Asset).isRecycled shouldBe true

        val folder = testApp.service.folder.add("Full")
        testContext.insertAsset(testContext.makeAsset(folder = Some(folder)))
        testApp.service.repository.setGrant(testContext.user.persistedId, repoId, Permissions.of(Permission.Organize))
        val delete = requests.delete(
          s"$host/htmx/folder/r/$repoId/",
          params = Map(Api.Field.ID -> folder.persistedId),
          cookies = testContext.cookies,
          check = false)
        delete.statusCode shouldBe 403
        delete.headers("content-type").head should startWith("text/plain")
        delete.text() should include("recycle 1 of these assets")
        testApp.service.folder.getById(folder.persistedId).isRecycled shouldBe false
    }
  }

  test("A Guest is refused Triage and the Trash wherever a route takes the view") {

    /**
     * Setup:
     *
     * A Guest logged in to a Library, and a User holding no Permission in another.
     *
     * Assertions:
     *
     * For the Guest, the Triage and Trash views and `triaged=true` are 403s on the main page, the search results and the map (in
     * JSON there), and `triaged=true` also on the People list and the advanced search panel's form and People options, which
     * refuse the Triage view too. The same requests for Browse answer 200.
     *
     * Edge cases:
     *
     * The User holding no Permission is a member and gets every view. The Guest's panel offers no view or triage filter.
     */
    val guestLibrary = loginAs(AccountType.Guest, Permissions.none)
    val guestCookies = testContext.cookies
    val member = testContext.persistUser()
    val userLibrary = testContext.persistRepository()
    testContext.grant(member, userLibrary, Permissions.none)
    val viewport = Map("viewport" -> "-90,-180,90,180", "zoom" -> "3")

    def viewRequests(host: String, repoId: String, params: Map[String, String]): List[(String, Map[String, String])] =
      List(
        s"$host/r/$repoId" -> params,
        s"$host/htmx/search/r/$repoId" -> params,
        s"$host/api/map/r/$repoId/cells" -> (params ++ viewport),
        s"$host/api/map/r/$repoId/bounds" -> params
      )

    val view = Api.Field.Search.VIEW
    val refused = List(
      Map(view -> altitude.core.Const.Search.View.TRIAGE),
      Map(view -> altitude.core.Const.Search.View.TRASHBIN),
      Map("triaged" -> "true"))

    withServer(App) {
      host =>
        def get(url: String, params: Map[String, String], cookies: Map[String, java.net.HttpCookie]) =
          requests.get(url, params = params, cookies = cookies, check = false)

        for (params <- refused; (url, query) <- viewRequests(host, guestLibrary, params)) withClue(s"[$url $query]: ") {
          val response = get(url, query, guestCookies)
          response.statusCode shouldBe 403
          if (url.contains("/api/")) ujson.read(response.text())("error").str should include("Guest")
        }
        withClue("the People list of triaged assets: ") {
          get(s"$host/htmx/people/r/$guestLibrary/list", Map("triaged" -> "true"), guestCookies).statusCode shouldBe 403
        }
        for (route <- List("options/people", "panel")) withClue(s"the advanced search panel's $route with triaged assets: ") {
          val url = s"$host/htmx/search/r/$guestLibrary/$route"
          get(url, Map("triaged" -> "true"), guestCookies).statusCode shouldBe 403
          get(url, Map(view -> altitude.core.Const.Search.View.TRIAGE), guestCookies).statusCode shouldBe 403
          get(url, Map.empty, guestCookies).statusCode shouldBe 200
        }
        withClue("the Guest's advanced search panel: ") {
          val panel = get(s"$host/htmx/search/r/$guestLibrary/panel", Map.empty, guestCookies).text()
          panel should include("<form")
          (panel should not).include("Search in")
          (panel should not).include(s"""name="$view"""")
          (panel should not).include("""name="triaged"""")
          (panel should not).include("data-options-params")
        }

        for ((url, query) <- viewRequests(host, guestLibrary, Map.empty)) withClue(s"[$url]: ") {
          get(url, query, guestCookies).statusCode shouldBe 200
        }
        val userCookies = cookiesOf(host, member)
        for (params <- refused; (url, query) <- viewRequests(host, userLibrary.persistedId, params))
          withClue(s"[$url $query]: ") {
            get(url, query, userCookies).statusCode shouldBe 200
          }
    }
  }

  test("A page renders the controls of the Permissions the account holds, and no other") {

    /**
     * Setup:
     *
     * A Library with an asset bearing a Face of a Person, another Person named "Taken", and with access to it: a User holding
     * every Permission, a User holding none, a Guest, a User holding none with the Create Libraries flag, Users holding Recycle,
     * Curate own or Edit metadata alone, and an Admin.
     *
     * Assertions:
     *
     * The User holding every Permission gets every gated control: the nav's Import, the batch Move, Recycle, Add to album and
     * Curate Selected, the Trash's Purge, the Face corrections, the rename pencil and the title's name editor. The User holding
     * none gets none of them but keeps the nav's Triage and Trash, which the Guest lacks, and a read-only People section. Recycle
     * follows the view: Recycle alone offers it in Browse, Curate own alone in Triage. Curate Selected needs a Curate Permission,
     * plus Recycle in Browse. A rename to a taken name offers the merge only to a holder of Organize, and renames nobody. New
     * library is offered to the Admin and the flagged User only.
     *
     * Edge cases:
     *
     * The main page's body lists the Permissions held, for the scripts.
     */
    val library = testContext.persistRepository()
    val repoId = library.persistedId
    val person = testApp.service.person.addPerson(altitude.core.models.Person())
    val asset = testContext.insertAsset()
    testContext.addTestFace(person, asset, altitude.test.TestContext.unitVector(1))

    def account(accountType: AccountType, permissions: Permissions, canCreateLibraries: Boolean = false) = {
      val user = testContext.persistUser(
        Some(testContext.makeUser().copy(accountType = accountType, canCreateLibraries = canCreateLibraries)))
      testContext.grant(user, library, permissions)
      user
    }
    val holder = account(AccountType.User, Permissions.all)
    val bare = account(AccountType.User, Permissions.none)
    val guest = account(AccountType.Guest, Permissions.none)
    val creator = account(AccountType.User, Permissions.none, canCreateLibraries = true)
    val recycler = account(AccountType.User, Permissions.of(Permission.Recycle))
    val curator = account(AccountType.User, Permissions.of(Permission.CurateOwn))
    val editor = account(AccountType.User, Permissions.of(Permission.EditMetadata))
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val taken = testApp.service.person.addPerson(altitude.core.models.Person(name = Some("Taken")))

    val newLibrary = s"/htmx/library/r/$repoId/dialogs/new"
    val controls = Map(
      "main" -> List(
        """class="menu import"""",
        "batch-mover",
        "batchAssetsRecycled",
        "batchAddToAlbumRequested",
        "batchCurateRequested"),
      "trash" -> List("purgeRecycleBin"),
      "detail" -> List("faceCombobox"),
      "people" -> List("""class="rename""""),
      "person" -> List(s"""/p/${person.persistedId}/name/edit"""")
    )

    withServer(App) {
      host =>
        def pages(cookies: Map[String, java.net.HttpCookie]): Map[String, String] = {
          def get(path: String, params: Map[String, String] = Map.empty) = {
            val response = requests.get(s"$host$path", params = params, cookies = cookies, check = false)
            response.statusCode shouldBe 200
            response.text()
          }
          Map(
            "main" -> get(s"/r/$repoId"),
            "trash" -> get(s"/htmx/search/r/$repoId", Map(Api.Field.Search.VIEW -> altitude.core.Const.Search.View.TRASHBIN)),
            "detail" -> get(s"/htmx/asset/r/$repoId/detail-panel/${asset.persistedId}"),
            "people" -> get(s"/htmx/people/r/$repoId/list", Map("buckets" -> altitude.core.Const.PeopleBucket.ALL.mkString(","))),
            "person" -> get(s"/htmx/search/r/$repoId", Map("personId" -> person.persistedId))
          )
        }

        val held = pages(cookiesOf(host, holder))
        for ((page, markers) <- controls; marker <- markers) withClue(s"[$page] holding every Permission: ") {
          held(page) should include(marker)
        }
        held("main") should include(s"""data-app-permissions="${Permissions.all.toList.mkString(" ")}"""")

        val none = pages(cookiesOf(host, bare))
        for ((page, markers) <- controls; marker <- markers) withClue(s"[$page] holding none: ") {
          (none(page) should not).include(marker)
        }
        none("main") should include("""data-app-permissions=""""")
        none("main") should include("""class="menu triage""")
        none("main") should include("""id="trash"""")
        none("detail") should include("""data-app-read-only="true"""")
        (none("main") should not).include(newLibrary)

        val guestMain = requests.get(s"$host/r/$repoId", cookies = cookiesOf(host, guest)).text()
        (guestMain should not).include("""class="menu triage""")
        (guestMain should not).include("""id="trash"""")

        requests.get(s"$host/r/$repoId", cookies = cookiesOf(host, creator)).text() should include(newLibrary)
        requests.get(s"$host/r/$repoId", cookies = cookiesOf(host, admin)).text() should include(newLibrary)
        (held("main") should not).include(newLibrary)

        def recycleOffered(cookies: Map[String, java.net.HttpCookie], view: String) =
          requests
            .get(s"$host/r/$repoId", params = Map(Api.Field.Search.VIEW -> view), cookies = cookies)
            .text()
            .contains("batchAssetsRecycled")
        val (browse, triage) = (altitude.core.Const.Search.View.DEFAULT, altitude.core.Const.Search.View.TRIAGE)
        val recyclerCookies = cookiesOf(host, recycler)
        val curatorCookies = cookiesOf(host, curator)
        withClue("Recycle, holding Recycle alone: ") {
          List(recycleOffered(recyclerCookies, browse), recycleOffered(recyclerCookies, triage)) shouldBe List(true, false)
        }
        withClue("Recycle, holding Curate own alone: ") {
          List(recycleOffered(curatorCookies, browse), recycleOffered(curatorCookies, triage)) shouldBe List(false, true)
        }
        // Curating recycles the frames not picked, which in Browse are sorted and so need Recycle
        def curateOffered(cookies: Map[String, java.net.HttpCookie], view: String) =
          requests
            .get(s"$host/r/$repoId", params = Map(Api.Field.Search.VIEW -> view), cookies = cookies)
            .text()
            .contains("batchCurateRequested")
        withClue("Curate Selected, holding Curate own alone: ") {
          List(curateOffered(curatorCookies, browse), curateOffered(curatorCookies, triage)) shouldBe List(false, true)
        }
        withClue("Curate Selected, holding Curate own and Recycle: ") {
          val both = cookiesOf(host, account(AccountType.User, Permissions.of(Permission.CurateOwn, Permission.Recycle)))
          List(curateOffered(both, browse), curateOffered(both, triage)) shouldBe List(true, true)
        }

        def renamedToTaken(cookies: Map[String, java.net.HttpCookie]) =
          requests
            .put(
              s"$host/htmx/people/r/$repoId/p/${person.persistedId}/name/edit",
              data = jsonBody(Api.Field.Person.NAME -> "Taken"),
              headers = Map("Content-Type" -> "application/json"),
              cookies = cookies
            )
            .text()
        val mergeOffer = altitude.core.Const.UI.MERGE_INTO_PERSON.format("Taken")
        renamedToTaken(cookiesOf(host, holder)) should include(mergeOffer)
        val editorAnswer = renamedToTaken(cookiesOf(host, editor))
        editorAnswer should include("Taken already exists")
        (editorAnswer should not).include(mergeOffer)
        // Neither rename went through: the name is taken
        testApp.service.person.getById(person.persistedId).name should not be Some("Taken")
        testApp.service.person.getById(taken.persistedId).name shouldBe Some("Taken")
    }
  }

  test("The folder, album and Location tabs say why there is nothing to add to an account without Organize") {

    /**
     * Setup:
     *
     * A User holding every Permission but Organize, a User holding Organize alone, and a Guest, each logged in to a Library.
     *
     * Assertions:
     *
     * For the first User and the Guest, each tab opens with a warning naming what it cannot create. The holder of Organize sees
     * none.
     */
    val tabs = List("folder" -> "folders", "album" -> "albums", "location" -> "Locations")
    val callers = List(
      (AccountType.User, Permissions(Permissions.all.mask & ~Permission.Organize.mask), true),
      (AccountType.User, Permissions.of(Permission.Organize), false),
      (AccountType.Guest, Permissions.none, true)
    )
    for ((accountType, permissions, warned) <- callers) withClue(s"[$accountType $permissions]: ") {
      testContext = new altitude.test.TestContext(testApp)
      val repoId = loginAs(accountType, permissions)

      withServer(App) {
        host =>
          for ((path, things) <- tabs) withClue(s"[$path]: ") {
            val tab = requests.get(s"$host/htmx/$path/r/$repoId/tab", cookies = testContext.cookies, check = false)
            tab.statusCode shouldBe 200
            val warning = s"""<p class="notice warning cannot-organize">${C.UI.CANNOT_ORGANIZE.format(things)}</p>"""
            tab.text().contains(warning) shouldBe warned
          }
      }
    }
  }

  test("A Guest is answered a triaged or a recycled asset by its ID as an unknown one") {

    /**
     * Setup:
     *
     * A Library holding a sorted, a triaged and a recycled asset, each with its Preview and original, and a Guest and a User
     * holding no Permission, both with access to it.
     *
     * Assertions:
     *
     * For the Guest, the triaged and the recycled asset are 404s through every route that takes an asset's ID, and the sorted
     * asset is a 200.
     *
     * Edge cases:
     *
     * The User holding no Permission is a member, and gets all three assets through every route.
     */
    val library = testContext.persistRepository()
    val repoId = library.persistedId
    val sorted = testContext.persistAsset()
    val triaged = testContext.persistAsset(isTriaged = true)
    val recycled = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))
    val guest = testContext.persistUser(Some(testContext.makeUser().copy(accountType = AccountType.Guest)))
    testContext.grant(guest, library, Permissions.none)
    val member = testContext.persistUser()
    testContext.grant(member, library, Permissions.none)

    /** The routes that take an asset's ID, each with whether it asks for JSON */
    def routes(id: String): List[(String, Boolean)] = List(
      s"/htmx/asset/r/$repoId/detail-panel/$id" -> false,
      s"/htmx/asset/r/$repoId/modals/asset-detail/$id" -> false,
      s"/htmx/asset/r/$repoId/modals/asset-detail/$id" -> true,
      s"/r/$repoId/asset/$id" -> false,
      s"/content/r/$repoId/${altitude.core.Const.DataStore.PREVIEW}/$id" -> false,
      s"/content/r/$repoId/${altitude.core.Const.DataStore.FILE}/$id" -> false
    )

    withServer(App) {
      host =>
        def status(cookies: Map[String, java.net.HttpCookie], path: String, json: Boolean) =
          requests
            .get(
              s"$host$path",
              cookies = cookies,
              headers = (if (json) Map("Accept" -> "application/json") else Map.empty) ++ Map("Accept-Encoding" -> "identity"),
              check = false)
            .statusCode

        val guestCookies = cookiesOf(host, guest)
        val memberCookies = cookiesOf(host, member)
        for (
          (asset, seen) <- List(sorted -> true, triaged -> false, recycled -> false); (path, json) <- routes(asset.persistedId)
        )
          withClue(s"[$path${if (json) " JSON" else ""}]: ") {
            status(guestCookies, path, json) shouldBe (if (seen) 200 else 404)
            status(memberCookies, path, json) shouldBe 200
          }
    }
  }
}
