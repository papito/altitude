package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query

@DoNotDiscover class LibraryServiceRecycleTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Recycle multiple assets") {

    /**
     * Setup:
     *
     * Eight assets in the root folder; five of them are recycled together, then recycled again.
     *
     * Assertions:
     *
     * Only the five are in the recycle bin and the other three stay live, and the recycled and sorted stats count exactly those
     * assets and their bytes.
     *
     * Edge cases:
     *
     * Recycling assets that are already recycled changes nothing, the stats included.
     */
    val assetsToRecycle = (1 to 5).map(_ => testContext.persistAsset())

    // not recycled and should stay that way
    val otherAssets = (1 to 3).map(_ => testContext.persistAsset())

    val idsToRecycle = assetsToRecycle.map(_.persistedId).toSet
    // recycle all assets
    testApp.service.library.recycleAssets(idsToRecycle)
    // recycling again should be a no-op
    testApp.service.library.recycleAssets(idsToRecycle)

    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe assetsToRecycle.size
    testApp.service.asset.query(new Query()).records.length shouldBe otherAssets.size

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe assetsToRecycle.size
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe assetsToRecycle.map(_.sizeBytes).sum

    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe otherAssets.size
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe otherAssets.map(_.sizeBytes).sum
  }

  test("Rename asset and attempt to rename a recycled asset") {

    /**
     * Setup:
     *
     * One asset in the root folder, renamed, then recycled.
     *
     * Assertions:
     *
     * The new name shows on the returned asset and on a fresh read, and renaming the asset once it is recycled is refused.
     *
     * Edge cases:
     *
     * A recycled asset cannot be renamed.
     */
    var asset: Asset = testContext.persistAsset()
    var updatedAsset: Asset = testApp.service.asset.rename(asset.persistedId, "newName")
    updatedAsset.fileName shouldBe "newName"

    // get the asset again to make sure it has been updated
    updatedAsset = testApp.service.asset.getById(asset.persistedId)
    updatedAsset.fileName shouldBe "newName"

    // attempt to rename a recycled asset
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[IllegalOperationException] {
      testApp.service.asset.rename(asset.persistedId, "newName2")
    }
  }

  test("Recycle asset") {

    /**
     * Setup:
     *
     * Two users each add an asset to the same repository, and the first user recycles one of them; then a second repository,
     * owned by the second user, is switched to.
     *
     * Assertions:
     *
     * Assets belong to the repository, not to the user: the first user sees both, recycling moves one of them from the live
     * assets to the recycle bin, and the second repository's recycle bin is empty.
     */
    testContext.persistAsset()

    // SECOND USER
    val user2 = testContext.persistUser()
    testApp.service.user.switchContextToUser(user2)

    testContext.persistAsset(user = Some(user2))

    // FIRST USER
    switchContextUser(testContext.users.head)
    testApp.service.asset.query(new Query()).records.length shouldBe 2

    val asset: Asset = testApp.service.asset.query(new Query()).records.head
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    // SECOND REPO
    val repo2 = testContext.persistRepository(user = Some(user2))
    switchContextRepo(repo2)

    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
  }

  test("Get recycled asset") {

    /**
     * Setup:
     *
     * One asset, recycled.
     *
     * Assertions:
     *
     * Just because an asset is recycled doesn't mean it can't be retrieved: it is still read by its ID.
     */
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.asset.getById(asset.persistedId)
  }

  test("Recycle folder assets") {

    /**
     * Setup:
     *
     * Two top-level folders and a child of the second, with an asset in each; both top-level folders are deleted.
     *
     * Assertions:
     *
     * Deleting a folder recycles its assets and those of its descendants, so all three assets end up recycled.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")

    val folder2_1: Folder = testApp.service.folder.add(name = "folder2_1", parentId = folder2.id)

    var asset1: Asset = testContext.persistAsset(folder = Some(folder1))
    var asset2: Asset = testContext.persistAsset(folder = Some(folder2))
    var asset3: Asset = testContext.persistAsset(folder = Some(folder2_1))

    testApp.service.library.deleteFolderById(folder1.persistedId)
    testApp.service.library.deleteFolderById(folder2.persistedId)

    asset1 = testApp.service.asset.getById(asset1.persistedId)
    asset2 = testApp.service.asset.getById(asset2.persistedId)
    asset3 = testApp.service.asset.getById(asset3.persistedId)

    asset1.isRecycled shouldBe true
    asset2.isRecycled shouldBe true
    asset3.isRecycled shouldBe true
  }

  test("Recycling a selection that includes a recycled asset counts only the newly recycled one") {

    /**
     * Setup:
     *
     * A Person with a Face on each of two assets. The first asset is recycled on its own, then both are recycled together.
     *
     * Assertions:
     *
     * The second recycle counts only the second asset: the recycled stats hold the two assets and their bytes, and the person's
     * face count drops to zero rather than below it.
     */
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = 2)
    val assets = testApp.service.asset.query(new Query()).records

    testApp.service.library.recycleAssets(Set(assets.head.persistedId))
    testApp.service.library.recycleAssets(assets.map(_.persistedId).toSet)

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe assets.map(_.sizeBytes).sum
    testApp.service.person.getById(person.persistedId).numOfFaces shouldBe 0
  }

  test("A selection larger than a statement's parameter limit is recycled and moved back") {

    /**
     * Setup:
     *
     * A Person with a Face on each of two assets, and a selection of the two among 70,000 unknown IDs, more than a PostgreSQL
     * statement takes parameters for. The selection is recycled, then moved into a new folder; the person's face counts are then
     * recycled and restored for the selection directly.
     *
     * Assertions:
     *
     * Recycling takes both Faces from the person and the move gives them back; the direct recycle and restore of the face counts
     * do the same.
     */
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = 2)
    val selection = amongManyUnknownIds(testApp.service.asset.query(new Query()).records.map(_.persistedId).toSet)
    val folder: Folder = testApp.service.folder.add("folder1")
    def numOfFaces: Int = testApp.service.person.getById(person.persistedId).numOfFaces

    testApp.service.library.recycleAssets(selection)
    numOfFaces shouldBe 0

    testApp.service.library.moveAssetsToFolder(selection, folder.persistedId)
    numOfFaces shouldBe 2

    testApp.service.person.recycleFacesForAssets(selection)
    numOfFaces shouldBe 0

    testApp.service.person.restoreFacesForAssets(selection)
    numOfFaces shouldBe 2
  }

  test("Moving recycled and live assets together into a folder restores the recycled ones' face counts") {

    /**
     * Setup:
     *
     * A Person with a Face on each of two assets in the root folder. The first asset is recycled, then both are moved into a new
     * folder.
     *
     * Assertions:
     *
     * The move takes the recycled asset out of the recycle bin and gives its Face back to the person, who counts both Faces
     * again, and the recycled stats are back to zero.
     */
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = 2)
    val assets = testApp.service.asset.query(new Query()).records
    val folder: Folder = testApp.service.folder.add("folder1")

    testApp.service.library.recycleAssets(Set(assets.head.persistedId))
    testApp.service.library.moveAssetsToFolder(assets.map(_.persistedId).toSet, folder.persistedId)

    testApp.service.person.getById(person.persistedId).numOfFaces shouldBe 2
    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
  }

  test("Moving an asset recycled from triage into a folder takes it out of the recycled stats, not the triage ones") {

    /**
     * Setup:
     *
     * A triaged asset that is recycled, which keeps its triage flag, then moved into a new folder.
     *
     * Assertions:
     *
     * Recycling moved it from the triage stats to the recycled ones, so the move takes it out of the recycled stats and into the
     * sorted ones, and the triage stats stay at zero.
     */
    val asset = testContext.persistAsset(isTriaged = true)
    val folder: Folder = testApp.service.folder.add("folder1")

    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folder.persistedId)

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats.getStatValue(Stats.TRIAGE_BYTES) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe asset.sizeBytes
  }
}
