package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.tagobjects.Slow
import org.scalatest.time.Minutes
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query

@DoNotDiscover class LibraryServiceRecycleTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {
  test("Recycle multiple assets") {

    /**
     * Setup:
     *
     * Eight assets in the root folder; five of them are recycled together, then recycled again.
     *
     * Assertions:
     *
     * Only the five are recycled, and the recycled and sorted stats count exactly the right assets and bytes.
     *
     * Edge cases:
     *
     * Recycling assets that are already recycled changes nothing, the stats included.
     */
    val assetsToRecycle = (1 to 5).map(_ => testContext.persistAsset())

    val otherAssets = (1 to 3).map(_ => testContext.persistAsset())

    val idsToRecycle = assetsToRecycle.map(_.persistedId).toSet
    testApp.service.library.recycleAssets(idsToRecycle)
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
     * The new name shows on the returned asset and on a fresh read.
     *
     * Edge cases:
     *
     * A recycled asset cannot be renamed.
     */
    var asset: Asset = testContext.persistAsset()
    var updatedAsset: Asset = testApp.service.asset.rename(asset.persistedId, "newName")
    updatedAsset.fileName shouldBe "newName"

    updatedAsset = testApp.service.asset.getById(asset.persistedId)
    updatedAsset.fileName shouldBe "newName"

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[IllegalOperationException] {
      testApp.service.asset.rename(asset.persistedId, "newName2")
    }
  }

  test("Recycle asset") {

    /**
     * Setup:
     *
     * Two users each add an asset to the same repository, and the first recycles one of them.
     *
     * Assertions:
     *
     * Assets belong to the repository, not the user: the first user sees both, recycling moves one to the recycle bin, and
     * another repository's recycle bin is empty.
     */
    testContext.persistAsset()

    val user2 = testContext.persistUser()
    testApp.service.user.switchContextToUser(user2)

    testContext.persistAsset(user = Some(user2))

    switchContextUser(testContext.users.head)
    testApp.service.asset.query(new Query()).records.length shouldBe 2

    val asset: Asset = testApp.service.asset.query(new Query()).records.head
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    val repo2 = testContext.persistRepository()
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
     * A recycled asset is still read by its ID.
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
     * Deleting a folder recycles its assets and those of its descendants: all three are recycled.
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
     * The second recycle counts only the second asset: the recycled stats hold two assets, and the person's face count drops to
     * zero, not below it.
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

  test("A selection larger than a statement's parameter limit is recycled and moved back", Slow) {

    /**
     * Setup:
     *
     * A Person with a Face on each of two assets, and 66,000 copies of the first asset, more than a PostgreSQL statement takes
     * parameters, with the stats reconciled to count them. Every asset is selected among 70,000 unknown IDs. The selection is
     * recycled, moved into a folder, then its face counts recycled and restored directly, in one transaction that is rolled back.
     *
     * Assertions:
     *
     * Recycling puts every asset in the Trash and takes both Faces from the person; the move takes every asset out of it and
     * gives the Faces back; the direct recycle and restore do the same.
     */
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = 2)
    val template = testApp.service.asset.query(new Query()).records.head
    val folder: Folder = testApp.service.folder.add("folder1")
    def numOfFaces: Int = testApp.service.person.getById(person.persistedId).numOfFaces
    def numRecycled: Long = countAssets("is_recycled = TRUE")

    atScale {
      seedCopies(template.persistedId, copies = 66000, folders = 1)
      testApp.service.stats.reconcile(): Unit
    } {
      val assetIds =
        query("SELECT id FROM asset WHERE repository_id = ?", RequestContext.getRepository.persistedId)
          .map(_("id").toString)
          .toSet
      assetIds.size shouldBe 66002
      val selection = amongManyUnknownIds(assetIds)

      testApp.service.library.recycleAssets(selection)
      numRecycled shouldBe 66002
      numOfFaces shouldBe 0

      testApp.service.library.moveAssetsToFolder(selection, folder.persistedId)
      numRecycled shouldBe 0
      numOfFaces shouldBe 2

      testApp.service.person.recycleFacesForAssets(selection)
      numOfFaces shouldBe 0

      testApp.service.person.restoreFacesForAssets(selection)
      numOfFaces shouldBe 2
    }
  }

  test("A folder holding more assets than a statement takes parameters is deleted", Slow) {

    /**
     * Setup:
     *
     * An asset in the root folder and 66,000 copies of it in a new folder, more than a PostgreSQL statement takes parameters,
     * with the stats reconciled to count them. The folder is deleted, in one transaction that is rolled back.
     *
     * Assertions:
     *
     * The folder is recycled and so is every copy, and the recycled stats count them.
     *
     * Edge cases:
     *
     * The asset in the root folder stays live.
     */
    val template = testContext.insertAsset()

    atScale {
      seedCopies(template.persistedId, copies = 66000, folders = 1)
      testApp.service.stats.reconcile(): Unit
    } {
      val seeded = query("SELECT id FROM folder WHERE repository_id = ? AND id LIKE 'seeded-folder-%'", repositoryId)
      val folderId = seeded.map(_("id").toString).loneElement

      testApp.service.library.deleteFolderById(folderId)

      testApp.service.folder.getById(folderId).isRecycled shouldBe true
      countAssets("is_recycled = TRUE") shouldBe 66000
      testApp.service.stats.getStats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 66000
      testApp.service.asset.getById(template.persistedId).isRecycled shouldBe false
    }
  }

  test("A Trash larger than a statement's parameter limit is emptied", Slow) {

    /**
     * Setup:
     *
     * A recycled asset and 66,000 recycled copies of it, more than a PostgreSQL statement takes parameters, with the stats
     * reconciled to count them. The Trash is emptied. The copies are committed, since the purge queue reads them on its own
     * threads, and whatever is left of them is deleted at the end.
     *
     * Assertions:
     *
     * Emptying the Trash marks every asset for purging and takes them all out of the recycled stats; the purge queue then deletes
     * every one.
     */
    val template = testContext.insertAsset()
    testApp.service.library.recycleAssets(Set(template.persistedId))

    try {
      testApp.txManager.withTransaction(seedCopies(template.persistedId, copies = 66000, folders = 1))
      testApp.service.stats.reconcile(): Unit
      testApp.service.stats.getStats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 66001

      testApp.service.library.purgeRecycleBin()

      val stats = testApp.service.stats.getStats
      stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
      stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
      testApp.txManager.asReadOnly(countAssets("is_purged = FALSE")) shouldBe 0

      // Waited out, so no purge of this test's runs into the next ones
      Eventually.eventually(Eventually.timeout(Span(10, Minutes)), Eventually.interval(Span(1, Seconds))) {
        testApp.txManager.asReadOnly(countAssets("TRUE")) shouldBe 0
      }
    } finally removeCopies()
  }

  test("Moving recycled and live assets together into a folder restores the recycled ones' face counts") {

    /**
     * Setup:
     *
     * A Person with a Face on each of two assets. The first asset is recycled, then both are moved into a new folder.
     *
     * Assertions:
     *
     * The move restores the recycled asset, the person counts both Faces again, and the recycled stats are back to zero.
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
     * Recycling moved it from the triage stats to the recycled ones, so the move counts it as sorted and the triage stats stay at
     * zero.
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

  test("Moving an asset marked for purging into a folder changes no stat and no row") {

    /**
     * Setup:
     *
     * A recycled asset, purged and moved into a new folder in one transaction, so the purge queue cannot delete its row in
     * between.
     *
     * Assertions:
     *
     * The row stays as the purge marked it, recycled in the root folder, and every stat is zero.
     */
    val asset = testContext.persistAsset()
    val folder: Folder = testApp.service.folder.add("folder1")
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.txManager.withTransaction {
      testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
      testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folder.persistedId)

      val unmoved: Asset = testApp.service.asset.getById(asset.persistedId)
      unmoved.isRecycled shouldBe true
      unmoved.folderId shouldBe testContext.repository.rootFolderId
    }

    storedStats.values.toSet shouldBe Set(0L)
  }

  test("Recycling and moving assets whose import has not finished changes no stat and no row") {

    /**
     * Setup:
     *
     * Two assets persisted by the import's index stage and never completed, so never counted: one in the root folder, recycled,
     * and one in triage, moved into a new folder.
     *
     * Assertions:
     *
     * Neither row changes and every stat stays at zero.
     */
    val unfinished = testApp.service.library.persistAndIndex(testContext.makeAsset())
    val unfinishedTriaged = testApp.service.library.persistAndIndex(testContext.makeAsset(isTriaged = true))
    val folder: Folder = testApp.service.folder.add("folder1")

    testApp.service.library.recycleAssets(Set(unfinished.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(unfinishedTriaged.persistedId), folder.persistedId)

    (testApp.service.asset.getById(unfinished.persistedId): Asset).isRecycled shouldBe false
    val unmoved: Asset = testApp.service.asset.getById(unfinishedTriaged.persistedId)
    unmoved.isTriaged shouldBe true
    unmoved.folderId shouldBe ""
    storedStats.values.toSet shouldBe Set(0L)
  }

  test("Recycling, moving and restoring another repository's assets changes neither repository") {

    /**
     * Setup:
     *
     * A live and a recycled asset in a second repository. In the first repository's context, the live one is recycled and the
     * recycled one moved into a folder, then restored.
     *
     * Assertions:
     *
     * Recycling and moving skip the foreign IDs and restoring fails as for an unknown ID. Neither repository's stats or assets
     * change.
     */
    val firstRepo: Repository = testContext.repository
    val folder: Folder = testApp.service.folder.add("folder1")
    val firstStats = storedStats

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)
    val live = testContext.persistAsset(repository = Some(secondRepo))
    val recycled = testContext.persistAsset(repository = Some(secondRepo))
    testApp.service.library.recycleAssets(Set(recycled.persistedId))
    val secondStats = storedStats

    switchContextRepo(firstRepo)
    testApp.service.library.recycleAssets(Set(live.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(recycled.persistedId), folder.persistedId)
    intercept[NotFoundException] {
      testApp.service.library.restoreRecycledAssets(Set(recycled.persistedId))
    }
    storedStats shouldBe firstStats

    switchContextRepo(secondRepo)
    storedStats shouldBe secondStats
    (testApp.service.asset.getById(live.persistedId): Asset).isRecycled shouldBe false
    val stillRecycled: Asset = testApp.service.asset.getById(recycled.persistedId)
    stillRecycled.isRecycled shouldBe true
    stillRecycled.folderId shouldBe secondRepo.rootFolderId
  }

  private def repositoryId: String = RequestContext.getRepository.persistedId

  /** The context repository's assets that meet the condition, counted in the caller's transaction */
  private def countAssets(condition: String): Long =
    query(s"SELECT COUNT(*) AS n FROM asset WHERE repository_id = ? AND $condition", repositoryId)
      .head("n")
      .toString
      .toLong
}
