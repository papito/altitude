package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions

/**
 * What a route answers for lack of a Permission that its decorators cannot see, since it depends on the asset or on a parameter
 * (the view a Guest asks for), and the controls a page renders only where the account holds the Permission. Which account may
 * call which route at all is `RouteTableTests`'.
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
     * A User holding Recycle and Organize, but no Curate Permission, logged in to a Library holding a triaged asset of theirs, a
     * recycled triaged asset of theirs, and a folder holding a sorted asset.
     *
     * Assertions:
     *
     * Recycling the triaged asset, and restoring the recycled one, is a JSON 403 whose `error` says how many assets the account
     * cannot recycle or restore, and nothing is recycled or restored.
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
     * The Guest's request for the Triage view or the Trash view of the main page, the search results and the map's cells and
     * bounds, or for the triaged assets Browse adds with `triaged=true` (the search, and the People list of Triage's explorer),
     * is a 403, in JSON from the map; the same requests for Browse answer 200.
     *
     * Edge cases:
     *
     * The User holding no Permission is a member, and gets every view.
     */
    val guestLibrary = loginAs(AccountType.Guest, Permissions.none)
    val guestCookies = testContext.cookies
    val member = testContext.persistUser()
    val userLibrary = testContext.persistRepository()
    testContext.grant(member, userLibrary, Permissions.none)
    val viewport = Map("viewport" -> "-90,-180,90,180", "zoom" -> "3")

    /** The requests of a view: the main page, the search results, the map's cells and bounds */
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
     * A Library with an asset bearing a Face of a Person, another Person named "Taken", and with access to it a User holding
     * every Permission, a User holding none, a Guest, a User holding none but with the Create Libraries flag, Users holding
     * Recycle, Curate own or Edit Metadata alone, and an Admin.
     *
     * Assertions:
     *
     * The User holding every Permission gets the nav's Import entry, the batch operations' Move, Recycle, Add to album and Curate
     * Selected, the Trash's Purge, the Detail panel's Face corrections, the People list's rename pencil and the results title's
     * name of a Person as the editor's trigger. The User holding none gets none of them, but the nav's Triage and Trash entries,
     * and the Detail panel's People section is read-only. The Guest gets neither Triage nor Trash in the nav. Recycle follows the
     * view: the User holding Recycle alone gets it in Browse and not in Triage, the User holding Curate own alone in Triage and
     * not in Browse; Curate Selected is offered in Triage for a Curate Permission and in Browse only with Recycle as well, since
     * the frames not picked are recycled. A rename to a taken name offers the merge into its holder only to a holder of Organize,
     * and renames nobody. New library in the nav's Library menu is the Admin's and the flagged User's, not the User's holding
     * every Permission without the flag.
     *
     * Edge cases:
     *
     * The main page's body lists the Permissions held, which the scripts build their controls by.
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
        // Curating a selection recycles the frames not picked, which in Browse are sorted, and take Recycle too
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

  test("A Guest is answered a triaged or a recycled asset by its ID as an unknown one") {

    /**
     * Setup:
     *
     * A Library holding a sorted asset, a triaged one and a recycled one, each imported with its Preview and original, and a
     * Guest and a User holding no Permission, both with access to it.
     *
     * Assertions:
     *
     * For the Guest, the triaged and the recycled asset are a 404 through every route that takes an asset's ID: the Detail panel,
     * the asset-detail modal and its JSON, the asset's page, its Preview and its original. The sorted asset is a 200 through
     * each.
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
