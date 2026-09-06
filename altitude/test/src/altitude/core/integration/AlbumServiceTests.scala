package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ shouldBe, shouldEqual }

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.Album
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Repository
import altitude.core.util.SearchQuery

@DoNotDiscover class AlbumServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def albumCounts: Map[String, Int] =
    testApp.service.album.getAll.map(album => album.persistedId -> album.numOfAssets).toMap

  private def searchAlbum(album: Album): Set[String] =
    val query = new SearchQuery(params = Map(FieldConst.Asset.IS_RECYCLED -> false), albumIds = Set(album.persistedId))
    testApp.service.library.search(query).records.map(r => (r: Asset).persistedId).toSet

  test("Album names are trimmed and cannot be empty") {
    intercept[ValidationException] {
      testApp.service.album.add("")
    }
    intercept[ValidationException] {
      testApp.service.album.add(" \t ")
    }

    val album: Album = testApp.service.album.add("  Summer 2024  ")
    album.name shouldEqual "Summer 2024"
  }

  test("Album names are unique per repository, ignoring case") {
    val album: Album = testApp.service.album.add("Trip")
    testApp.service.album.add("Other")

    intercept[DuplicateException] {
      testApp.service.album.add("trip")
    }

    intercept[DuplicateException] {
      testApp.service.album.rename(album.persistedId, "OTHER")
    }

    // Changing only the casing of an album's own name is allowed
    testApp.service.album.rename(album.persistedId, "TRIP")
    (testApp.service.album.getById(album.persistedId): Album).name shouldEqual "TRIP"
  }

  test("Albums are listed by name with their asset counts") {
    val beach: Album = testApp.service.album.add("beach")
    val alps: Album = testApp.service.album.add("Alps")
    val empty: Album = testApp.service.album.add("Empty")

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()

    testApp.service.album.addAssets(beach.persistedId, Set(asset1.persistedId, asset2.persistedId))
    testApp.service.album.addAssets(alps.persistedId, Set(asset1.persistedId))

    val albums = testApp.service.album.getAll
    albums.map(_.name) shouldEqual List("Alps", "beach", "Empty")

    val counts = albumCounts
    counts(beach.persistedId) shouldEqual 2
    counts(alps.persistedId) shouldEqual 1
    counts(empty.persistedId) shouldEqual 0
  }

  test("Rename an album") {
    val album: Album = testApp.service.album.add("before")

    testApp.service.album.rename(album.persistedId, " after ")

    val renamed: Album = testApp.service.album.getById(album.persistedId)
    renamed.name shouldEqual "after"
  }

  test("Deleting an album removes its memberships and leaves the assets alone") {
    val album: Album = testApp.service.album.add("doomed")
    val other: Album = testApp.service.album.add("other")
    val asset: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId))
    testApp.service.album.addAssets(other.persistedId, Set(asset.persistedId))

    testApp.service.album.deleteById(album.persistedId)

    intercept[NotFoundException] {
      testApp.service.album.getById(album.persistedId)
    }

    val stillThere: Asset = testApp.service.asset.getById(asset.persistedId)
    stillThere.isRecycled shouldBe false
    testApp.service.album.getAssetIds(other.persistedId) shouldEqual Set(asset.persistedId)
  }

  test("Adding assets to an album is idempotent and an asset can be in many albums") {
    val album1: Album = testApp.service.album.add("one")
    val album2: Album = testApp.service.album.add("two")
    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()

    testApp.service.album.addAssets(album1.persistedId, Set(asset1.persistedId)) shouldEqual 1
    // asset1 is already there - only asset2 is new
    testApp.service.album.addAssets(album1.persistedId, Set(asset1.persistedId, asset2.persistedId)) shouldEqual 1
    testApp.service.album.addAssets(album2.persistedId, Set(asset1.persistedId)) shouldEqual 1

    testApp.service.album.getAssetIds(album1.persistedId) shouldEqual Set(asset1.persistedId, asset2.persistedId)
    testApp.service.album.getAssetIds(album2.persistedId) shouldEqual Set(asset1.persistedId)

    // The asset itself is untouched: still in its folder, still not recycled
    val asset: Asset = testApp.service.asset.getById(asset1.persistedId)
    asset.folderId shouldEqual testContext.repository.rootFolderId
    asset.isRecycled shouldBe false
  }

  test("Recycled and unknown assets are not added to an album") {
    val album: Album = testApp.service.album.add("album")
    val asset: Asset = testContext.persistAsset()
    val recycled: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val added = testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId, recycled.persistedId, "bogus"))

    added shouldEqual 1
    testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(asset.persistedId)
  }

  test("Removing assets from an album leaves the assets alone") {
    val album: Album = testApp.service.album.add("album")
    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(asset1.persistedId, asset2.persistedId))

    testApp.service.album.removeAssets(album.persistedId, Set(asset1.persistedId)) shouldEqual 1

    testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(asset2.persistedId)
    (testApp.service.asset.getById(asset1.persistedId): Asset).isRecycled shouldBe false
  }

  test("Recycling an asset removes it from every album and restoring does not add it back") {
    val album1: Album = testApp.service.album.add("one")
    val album2: Album = testApp.service.album.add("two")
    val asset: Asset = testContext.persistAsset()
    val keeper: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album1.persistedId, Set(asset.persistedId, keeper.persistedId))
    testApp.service.album.addAssets(album2.persistedId, Set(asset.persistedId))

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.album.getAssetIds(album1.persistedId) shouldEqual Set(keeper.persistedId)
    testApp.service.album.getAssetIds(album2.persistedId) shouldEqual Set()

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    testApp.service.album.getAssetIds(album1.persistedId) shouldEqual Set(keeper.persistedId)
    testApp.service.album.getAssetIds(album2.persistedId) shouldEqual Set()
  }

  test("Deleting a folder removes its assets from albums") {
    val album: Album = testApp.service.album.add("album")
    val folder: Folder = testApp.service.folder.add("folder")
    val inFolder: Asset = testContext.persistAsset(folder = Some(folder))
    val atRoot: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(inFolder.persistedId, atRoot.persistedId))

    testApp.service.library.deleteFolderById(folder.persistedId)

    testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(atRoot.persistedId)
  }

  test("Deleting an asset row removes its album memberships through the schema") {
    val album: Album = testApp.service.album.add("album")
    val asset: Asset = testContext.persistAsset()
    val keeper: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId, keeper.persistedId))

    // What the purge pipeline and dangling-asset pruning do: delete the row itself
    testApp.service.asset.deleteById(asset.persistedId)

    testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(keeper.persistedId)
    albumCounts(album.persistedId) shouldEqual 1
  }

  test("Albums are scoped to the repository") {
    val firstRepo: Repository = testContext.repository
    val album: Album = testApp.service.album.add("shared name")
    val asset: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId))

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)

    // Same name is fine in another repository, and the first repository's albums are not visible
    testApp.service.album.add("shared name")
    testApp.service.album.getAll.map(_.name) shouldEqual List("shared name")
    albumCounts.values.sum shouldEqual 0

    switchContextRepo(firstRepo)
    testApp.service.album.getAll.length shouldEqual 1
    albumCounts(album.persistedId) shouldEqual 1
  }

  test("Searching by album returns only its non-recycled assets") {
    val album: Album = testApp.service.album.add("album")
    val folder: Folder = testApp.service.folder.add("folder")
    val inAlbum1: Asset = testContext.persistAsset()
    val inAlbum2: Asset = testContext.persistAsset(folder = Some(folder))
    val notInAlbum: Asset = testContext.persistAsset()
    testApp.service.album.addAssets(album.persistedId, Set(inAlbum1.persistedId, inAlbum2.persistedId))

    // Assets from any folder show up, ones outside the album do not
    searchAlbum(album) shouldEqual Set(inAlbum1.persistedId, inAlbum2.persistedId)
    testApp.service.library.search(new SearchQuery()).records.length shouldEqual 3

    testApp.service.library.recycleAssets(Set(inAlbum2.persistedId))
    searchAlbum(album) shouldEqual Set(inAlbum1.persistedId)

    val emptyAlbum: Album = testApp.service.album.add("empty")
    searchAlbum(emptyAlbum) shouldEqual Set()
    notInAlbum.persistedId.nonEmpty shouldBe true
  }
}
