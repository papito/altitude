package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe, shouldEqual }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Album
import altitude.core.models.Asset

@DoNotDiscover class AlbumControllerTests extends ControllerTestCore {

  test("Album list JSON is sorted by name and carries asset counts") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the albums "Zermatt" and "beach", added in that order, and one imported asset in
     * "beach".
     *
     * Assertions:
     *
     * The album list API answers JSON ordered by name ignoring case, with each album's ID and asset count.
     *
     * Edge cases:
     *
     * "Zermatt" comes before "beach" byte-wise and in insertion order, but after it ignoring case.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val zermatt: Album = testApp.service.album.add("Zermatt")
        val beach: Album = testApp.service.album.add("beach")
        val asset: Asset = testContext.persistAsset()
        testApp.service.album.addAssets(beach.persistedId, Set(asset.persistedId))

        val response = requests.get(s"$host/api/album/r/$repoId/list", cookies = testContext.cookies)

        response.statusCode shouldBe 200
        response.headers("content-type").head should include("application/json")

        val albums = ujson.read(response.text()).arr
        albums.map(_("name").str) shouldEqual List("beach", "Zermatt")
        albums.map(_("id").str) shouldEqual List(beach.persistedId, zermatt.persistedId)
        albums.map(_("numOfAssets").num) shouldEqual List(1, 0)
    }
  }

  test("Assets are added to and removed from an album") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one empty album and two imported assets.
     *
     * Assertions:
     *
     * The album assets API adds both assets, then removes one, reporting how many it added or removed, and the album's membership
     * follows each call.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val album: Album = testApp.service.album.add("album")
        val asset1: Asset = testContext.persistAsset()
        val asset2: Asset = testContext.persistAsset()

        val payload = ujson.Obj(
          Api.Field.Album.ALBUM_ID -> album.persistedId,
          Api.Field.ASSET_IDS -> ujson.Arr(asset1.persistedId, asset2.persistedId)
        )

        val addResponse = requests.put(
          s"$host/api/album/r/$repoId/assets",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(payload),
          cookies = testContext.cookies)

        addResponse.statusCode shouldBe 200
        ujson.read(addResponse.text())("added").num shouldBe 2
        testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(asset1.persistedId, asset2.persistedId)

        val removePayload = ujson.Obj(
          Api.Field.Album.ALBUM_ID -> album.persistedId,
          Api.Field.ASSET_IDS -> ujson.Arr(asset1.persistedId)
        )

        val removeResponse = requests.delete(
          s"$host/api/album/r/$repoId/assets",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(removePayload),
          cookies = testContext.cookies)

        removeResponse.statusCode shouldBe 200
        ujson.read(removeResponse.text())("removed").num shouldBe 1
        testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(asset2.persistedId)
    }
  }

  test("Search results are filtered by album and report the album as their scope") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one album and two imported assets, only one of them in the album.
     *
     * Assertions:
     *
     * A search scoped to the album renders only its member, marks the results with the album as their scope, and keeps the album
     * in the URL the browser is told to show.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val album: Album = testApp.service.album.add("album")
        val inAlbum: Asset = testContext.persistAsset()
        val notInAlbum: Asset = testContext.persistAsset()
        testApp.service.album.addAssets(album.persistedId, Set(inAlbum.persistedId))

        val response = requests.get(
          s"$host/htmx/search/r/$repoId",
          params = Map(Api.Field.Search.ALBUM_ID -> album.persistedId),
          cookies = testContext.cookies)

        response.statusCode shouldBe 200
        response.text() should include(s"""data-results-album-id="${album.persistedId}"""")
        response.text() should include(s"""id="asset-${inAlbum.persistedId}"""")
        (response.text() should not).include(s"""id="asset-${notInAlbum.persistedId}"""")
        response.headers("hx-replace-url").head should include(s"${Api.Field.Search.ALBUM_ID}=${album.persistedId}")
    }
  }

  test("Adding assets to or removing them from an unknown album, or another repository's, is a JSON 404") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an asset, and a second repository of theirs with the album "Eve's album" holding an
     * asset of that repository.
     *
     * Assertions:
     *
     * Through the first repository, adding the user's asset to "Eve's album" and removing her asset from it answer a JSON 404, as
     * both do for an album ID that does not exist, and no answer carries the album's name. No row of either repository changes.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val mine = testContext.insertAsset(testContext.makeAsset(repository = Some(repo)))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eves: Album = testApp.service.album.add("Eve's album")
        val theirs = testContext.insertAsset(testContext.makeAsset(repository = Some(otherRepo)))
        testApp.service.album.addAssets(eves.persistedId, Set(theirs.persistedId))
        testApp.service.repository.switchContextToRepository(repo)
        val ownBefore = testContext.rowsOf(repo)
        val othersBefore = testContext.rowsOf(otherRepo)

        def membership(method: requests.Requester, albumId: String, assetId: String): requests.Response =
          method(
            s"$host/api/album/r/$repoId/assets",
            headers = Map("Content-Type" -> "application/json"),
            data = ujson.write(ujson.Obj(Api.Field.Album.ALBUM_ID -> albumId, Api.Field.ASSET_IDS -> ujson.Arr(assetId))),
            cookies = testContext.cookies,
            check = false
          )

        for (albumId <- List(java.util.UUID.randomUUID.toString, eves.persistedId)) withClue(s"[$albumId]: ") {
          for (
            answer <- List(
              membership(requests.put, albumId, mine.persistedId),
              membership(requests.delete, albumId, theirs.persistedId))
          ) {
            answer.statusCode shouldBe 404
            answer.headers("content-type") shouldBe Seq("application/json")
            ujson.read(answer.text()).obj.keySet shouldBe Set("error")
            (answer.text() should not).include("Eve")
          }
        }

        testContext.rowsOf(repo) shouldBe ownBefore
        testContext.rowsOf(otherRepo) shouldBe othersBefore
    }
  }
}
