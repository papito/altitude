package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldEqual }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Album

@DoNotDiscover class AlbumActionControllerTests extends ControllerTestCore {

  test("Albums tab and dialogs render") {
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
}
