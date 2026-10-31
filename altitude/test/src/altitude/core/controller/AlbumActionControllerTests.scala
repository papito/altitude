package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Album

@DoNotDiscover class AlbumActionControllerTests extends ControllerTestCore {
  private val jsonHeaders = Map("Content-Type" -> "application/json")

  /** A refused submission answers with the dialog's form carrying an error, replacing the submitted form in place */
  private def validation(response: requests.Response, form: String): Unit = {
    response.statusCode shouldBe 200
    response.headers("hx-retarget") shouldBe Seq("this")
    response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
    response.text() should include(s"""id="$form"""")
    response.text() should include("""class="error"""")
  }

  test("Albums tab and dialogs render") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one album, "holiday".
     *
     * Assertions:
     *
     * The Albums tab renders the hosts the client fills in - the list, the add controls and the empty state - and the add, rename
     * and delete dialogs render, the rename and delete ones naming the album.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val album: Album = testApp.service.album.add("holiday")

        val tab = requests.get(s"$host/htmx/album/r/$repoId/tab", cookies = testContext.cookies, check = false)
        tab.statusCode shouldBe 200
        tab.text() should include("""id="albumList"""")
        // The add controls are built client-side into these hosts
        tab.text() should include("""id="albumActions"""")
        tab.text() should include("""id="noAlbums"""")

        val add = requests.get(s"$host/htmx/album/r/$repoId/dialogs/add-album", cookies = testContext.cookies, check = false)
        add.statusCode shouldBe 200
        add.text() should include("""id="addAlbum"""")

        val rename = requests.get(
          s"$host/htmx/album/r/$repoId/dialogs/rename-album",
          params = Map("id" -> album.persistedId),
          cookies = testContext.cookies,
          check = false)
        rename.statusCode shouldBe 200
        rename.text() should include("""id="renameAlbum"""")
        rename.text() should include(album.name)

        val delete = requests.get(
          s"$host/htmx/album/r/$repoId/dialogs/delete-album",
          params = Map("id" -> album.persistedId),
          cookies = testContext.cookies,
          check = false)
        delete.statusCode shouldBe 200
        delete.text() should include("""id="deleteAlbum"""")
        delete.text() should include(album.name)
    }
  }

  test("Adding an album succeeds, and a validation error replaces the dialog form in place") {

    /**
     * Setup:
     *
     * A logged-in user's repository with no albums, and album names posted to the add endpoint as JSON.
     *
     * Assertions:
     *
     * A valid name creates the album, trimmed, with an empty response. A blank or duplicate name creates nothing and answers with
     * the add dialog's form carrying the error, retargeted to replace the submitted form in place.
     *
     * Edge cases:
     *
     * Surrounding whitespace is trimmed, and a name that differs from an existing one only by case is a duplicate.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def post(name: String) = requests.post(
          s"$host/htmx/album/r/$repoId/add",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(ujson.Obj(Api.Field.Album.NAME -> name)),
          cookies = testContext.cookies,
          check = false
        )

        val created = post("  Holiday ")
        created.statusCode shouldBe 200
        created.text() shouldBe ""
        testApp.service.album.getAll.map(_.name) shouldEqual List("Holiday")

        val blank = post("")
        blank.statusCode shouldBe 200
        blank.headers("hx-retarget") shouldBe Seq("this")
        blank.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
        blank.text() should include("""id="addAlbum"""")
        blank.text() should include("""class="error"""")

        val duplicate = post("holiday")
        duplicate.headers("hx-retarget") shouldBe Seq("this")
        duplicate.text() should include("""class="error"""")
        testApp.service.album.getAll.length shouldBe 1
    }
  }

  test("Renaming and deleting an album") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one album, "before".
     *
     * Assertions:
     *
     * A rename persists the new name, a blank name answers with the rename dialog's form carrying the error in place of the
     * submitted one, and a delete removes the album.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val album: Album = testApp.service.album.add("before")

        val renamed = requests.put(
          s"$host/htmx/album/r/$repoId/rename",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(ujson.Obj(Api.Field.Album.NAME -> "after", Api.Field.ID -> album.persistedId)),
          cookies = testContext.cookies,
          check = false
        )
        renamed.statusCode shouldBe 200
        (testApp.service.album.getById(album.persistedId): Album).name shouldEqual "after"

        val invalid = requests.put(
          s"$host/htmx/album/r/$repoId/rename",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(ujson.Obj(Api.Field.Album.NAME -> "", Api.Field.ID -> album.persistedId)),
          cookies = testContext.cookies,
          check = false
        )
        invalid.headers("hx-retarget") shouldBe Seq("this")
        invalid.text() should include("""id="renameAlbum"""")
        invalid.text() should include("""class="error"""")

        val deleted = requests.delete(
          s"$host/htmx/album/r/$repoId/",
          params = Map("id" -> album.persistedId),
          cookies = testContext.cookies,
          check = false)
        deleted.statusCode shouldBe 200
        testApp.service.album.getAll shouldEqual List()
    }
  }

  test("The Add to album dialog lists the albums and offers a new one") {

    /**
     * Setup:
     *
     * A logged-in user's repository, first with no albums, then with the albums "holiday" and "beach".
     *
     * Assertions:
     *
     * The dialog lists every album by name, with the bare name in `data-name`, followed by the "New album…" option. While there
     * are albums, an album is selected and the new album's name field is hidden; with none, "New album…" is the only option and
     * the name field shows and takes the focus.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def dialog() = {
          val response =
            requests.get(s"$host/htmx/album/r/$repoId/dialogs/add-to-album", cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }

        val empty = dialog()
        empty should include("""id="addToAlbum"""")
        empty should include(s"""data-app-modal-title="${Const.UI.ADD_TO_ALBUM_DIALOG_TITLE}"""")
        empty should include(Const.UI.NEW_ALBUM_OPTION)
        empty should include("""data-app-dialog-autofocus-selector="#field-newAlbumName"""")
        (empty should not).include("""id="newAlbum" hidden""")

        testApp.service.album.add("holiday")
        testApp.service.album.add("beach")

        val listed = dialog()
        listed should include("""data-name="holiday"""")
        listed should include("""data-name="beach"""")
        listed should include(Const.UI.NEW_ALBUM_OPTION)
        listed should include("""data-app-dialog-autofocus-selector="#field-albumId"""")
        listed should include("""id="newAlbum" hidden""")
    }
  }

  test("The selection is added to an album, or to a new album the dialog creates") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the album "Holiday" and two imported assets, and a second repository holding the album
     * "Elsewhere". The selection is posted as the dialog posts it: a comma-separated `assetIds` field.
     *
     * Assertions:
     *
     * Adding to an album writes the memberships and reports in the success detail how many assets the server added, the album's
     * ID and name, and that no album was created. A name with no album ID creates that album, trimmed, holding the selection, and
     * says it was created. Every refused submission answers with the dialog's form carrying the error and changes nothing.
     *
     * Edge cases:
     *
     * A repeated add reports 0; a new name taken by an album in another case; neither an album nor a name; a malformed album or
     * asset ID; an empty selection; the other repository's album.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign: Album = testApp.service.album.add("Elsewhere")
        testApp.service.repository.switchContextToRepository(repo)

        val album: Album = testApp.service.album.add("Holiday")
        val first = testContext.persistAsset(repository = Some(repo))
        val second = testContext.persistAsset(repository = Some(repo))
        val selection = s"${first.persistedId},${second.persistedId}"

        def put(payload: ujson.Obj) = requests.put(
          s"$host/htmx/album/r/$repoId/assets",
          headers = jsonHeaders,
          data = payload.toString,
          cookies = testContext.cookies,
          check = false)
        def detail(response: requests.Response): ujson.Value =
          ujson.read(response.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head)

        val membership = put(ujson.Obj(Api.Field.Album.ALBUM_ID -> album.persistedId, Api.Field.ASSET_IDS -> selection))
        membership.statusCode shouldBe 200
        membership.text() shouldBe ""
        detail(membership)("added").num shouldBe 2
        detail(membership)(Api.Field.Album.ALBUM_ID).str shouldBe album.persistedId
        detail(membership)(Api.Field.Album.NAME).str shouldBe "Holiday"
        detail(membership)("created").bool shouldBe false
        testApp.service.album.getAssetIds(album.persistedId) shouldBe Set(first.persistedId, second.persistedId)

        val repeat = put(ujson.Obj(Api.Field.Album.ALBUM_ID -> album.persistedId, Api.Field.ASSET_IDS -> first.persistedId))
        detail(repeat)("added").num shouldBe 0

        val created =
          put(ujson.Obj(Api.Field.Album.ALBUM_ID -> "", Api.Field.Album.NAME -> "  Beach ", Api.Field.ASSET_IDS -> selection))
        created.statusCode shouldBe 200
        detail(created)("added").num shouldBe 2
        detail(created)(Api.Field.Album.NAME).str shouldBe "Beach"
        detail(created)("created").bool shouldBe true
        val beachId = detail(created)(Api.Field.Album.ALBUM_ID).str
        testApp.service.album.getAssetIds(beachId) shouldBe Set(first.persistedId, second.persistedId)

        validation(
          put(ujson.Obj(Api.Field.Album.ALBUM_ID -> "", Api.Field.Album.NAME -> "holiday", Api.Field.ASSET_IDS -> selection)),
          "addToAlbum")
        validation(
          put(ujson.Obj(Api.Field.Album.ALBUM_ID -> "", Api.Field.Album.NAME -> " ", Api.Field.ASSET_IDS -> selection)),
          "addToAlbum")
        validation(put(ujson.Obj(Api.Field.Album.ALBUM_ID -> "bad-id", Api.Field.ASSET_IDS -> selection)), "addToAlbum")
        validation(put(ujson.Obj(Api.Field.Album.ALBUM_ID -> album.persistedId, Api.Field.ASSET_IDS -> "bad-id")), "addToAlbum")
        validation(put(ujson.Obj(Api.Field.Album.ALBUM_ID -> album.persistedId, Api.Field.ASSET_IDS -> " , ")), "addToAlbum")
        validation(
          put(ujson.Obj(Api.Field.Album.ALBUM_ID -> foreign.persistedId, Api.Field.ASSET_IDS -> selection)),
          "addToAlbum")

        // The duplicate, blank and refused submissions created nothing
        testApp.service.album.getAll.map(_.name) shouldEqual List("Beach", "Holiday")
    }
  }

  test("The Add to album routes require authentication") {

    /**
     * Setup:
     *
     * A repository, and a client that accepts JSON and has not logged in, so the routes answer 401 instead of redirecting to the
     * login page.
     *
     * Assertions:
     *
     * The Add to album dialog and the membership action answer 401.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val headers = Map("Accept" -> "application/json")

    withServer(App) {
      host =>
        requests
          .get(s"$host/htmx/album/r/$repoId/dialogs/add-to-album", headers = headers, check = false)
          .statusCode shouldBe 401
        requests.put(s"$host/htmx/album/r/$repoId/assets", headers = headers, check = false).statusCode shouldBe 401
    }
  }
}
