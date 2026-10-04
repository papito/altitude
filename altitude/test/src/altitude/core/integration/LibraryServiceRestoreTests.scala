package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ empty, shouldBe }

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query

@DoNotDiscover class LibraryServiceRestoreTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Move recycled asset to folder") {

    /**
     * Setup:
     *
     * One asset in the root folder, recycled, then moved into a new folder.
     *
     * Assertions:
     *
     * Moving a recycled asset into a folder restores it: it leaves the recycle bin, is live again, and is in the destination
     * folder.
     */
    val asset: Asset = testContext.persistAsset()
    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    val folder1: Folder = testApp.service.folder.add("folder1")

    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folder1.persistedId)
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.asset.query(new Query()).records.length shouldBe 1

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
      )
      .records
      .length shouldBe 1
  }

  test("Restore recycled assets") {

    /**
     * Setup:
     *
     * One asset, recycled and then restored.
     *
     * Assertions:
     *
     * The asset is live again.
     */
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))
    testApp.service.asset.query(new Query()).isEmpty shouldBe false
  }

  test("Restore an asset that was imported again") {

    /**
     * Setup:
     *
     * An asset in a folder, imported and recycled, after which a second copy of the same content (the same checksum) is imported.
     *
     * Assertions:
     *
     * Restoring the recycled copy is skipped and reported as a duplicate, and the copy stays in the recycle bin.
     *
     * Edge cases:
     *
     * Importing content whose earlier copy is in the recycle bin is allowed.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val assetWithFolder = testContext.makeAsset().copy(folderId = folder1.persistedId)
    val dataAsset = testContext.makeAssetWithData(asset = Some(assetWithFolder))
    // The pipeline consumes a staged file, so the second import needs its own copy
    val secondCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))
    val persistedAsset: Asset = testApp.service.library.addAsset(dataAsset)

    // recycle the asset
    testApp.service.library.recycleAssets(Set(persistedAsset.persistedId))

    // import a new copy of it (should be allowed)
    testApp.service.library.addAsset(secondCopy)

    // now restore the previously deleted copy: its content is live again, so it is skipped and reported
    val result = testApp.service.library.restoreRecycledAssets(Set(persistedAsset.persistedId))
    result.restored shouldBe Set.empty
    result.duplicates shouldBe Set(persistedAsset.persistedId)
    (testApp.service.asset.getById(persistedAsset.persistedId): Asset).isRecycled shouldBe true
  }

  test("The trash holds every recycled copy of the same content, and only one of them comes back") {

    /**
     * Setup:
     *
     * An asset imported and recycled, after which a second copy of the same content is imported and recycled too. Both copies are
     * then restored together, and a third copy of the content is imported.
     *
     * Assertions:
     *
     * Both copies are in the recycle bin. Restoring them brings one back and reports the other as a duplicate, which stays in the
     * recycle bin, and importing the content while a copy of it is live is refused as a duplicate.
     */
    val dataAsset = testContext.makeAssetWithData()
    // The pipeline consumes a staged file, so each further import needs its own copy
    val secondCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))
    val thirdCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))

    val first: Asset = testApp.service.library.addAsset(dataAsset)
    testApp.service.library.recycleAssets(Set(first.persistedId))
    val second: Asset = testApp.service.library.addAsset(secondCopy)
    testApp.service.library.recycleAssets(Set(second.persistedId))

    val copies = Set(first.persistedId, second.persistedId)
    copies.foreach(id => (testApp.service.asset.getById(id): Asset).isRecycled shouldBe true)

    val result = testApp.service.library.restoreRecycledAssets(copies)
    result.restored.size shouldBe 1
    result.duplicates shouldBe copies -- result.restored
    result.duplicates.foreach(id => (testApp.service.asset.getById(id): Asset).isRecycled shouldBe true)

    intercept[DuplicateException](testApp.service.library.addAsset(thirdCopy))
  }

  test("Restore skips an asset whose content is live again and restores the rest") {

    /**
     * Setup:
     *
     * Two assets in a folder, both recycled, after which a second copy of one of them is imported; both are restored together.
     *
     * Assertions:
     *
     * The restore is partial: the asset with no live copy is restored, the other is reported as a duplicate and stays in the
     * recycle bin, and the stats count one recycled and two sorted assets.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val restorable: Asset = testContext.persistAsset(folder = Some(folder1))

    val dataAsset = testContext.makeAssetWithData(asset = Some(testContext.makeAsset(folder = Some(folder1))))
    val secondCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))
    val duplicate: Asset = testApp.service.library.addAsset(dataAsset)

    testApp.service.library.recycleAssets(Set(restorable.persistedId, duplicate.persistedId))
    testApp.service.library.addAsset(secondCopy)

    val result = testApp.service.library.restoreRecycledAssets(Set(restorable.persistedId, duplicate.persistedId))

    result.restored shouldBe Set(restorable.persistedId)
    result.duplicates shouldBe Set(duplicate.persistedId)
    (testApp.service.asset.getById(restorable.persistedId): Asset).isRecycled shouldBe false
    (testApp.service.asset.getById(duplicate.persistedId): Asset).isRecycled shouldBe true

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
    // the restored asset and the copy imported again
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
  }

  test("A restore that fails restores nothing") {

    /**
     * Setup:
     *
     * One recycled asset in a folder, restored together with an unknown asset ID.
     *
     * Assertions:
     *
     * The restore fails on the unknown ID and restores nothing: the asset stays recycled and the stats are unchanged.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[NotFoundException] {
      testApp.service.library.restoreRecycledAssets(Set(asset.persistedId, "00000000-0000-0000-0000-000000000000"))
    }

    (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe true
    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
  }

  test("Restoring an asset marked for purging restores nothing") {

    /**
     * Setup:
     *
     * One asset in a folder, recycled; then, in one transaction, so the purge queue cannot delete its row in between, it is
     * purged and restored.
     *
     * Assertions:
     *
     * The restore reports nothing restored and no duplicate, the row stays recycled, and the stats only lose the purged asset:
     * nothing is sorted and nothing recycled.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.txManager.withTransaction {
      testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
      val result = testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

      result shouldBe RestoreResult(restored = Set.empty, duplicates = Set.empty)
      (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe true
    }

    storedStats.values.toSet shouldBe Set(0L)
  }

  test("Restoring an asset into a recycled folder should restore the folder") {

    /**
     * Setup:
     *
     * An asset in a folder, recycled, after which the folder itself is deleted.
     *
     * Assertions:
     *
     * Restoring the asset brings its folder back out of the recycle bin.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.library.deleteFolderById(folder1.persistedId)

    val deletedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    deletedFolder.isRecycled shouldBe true

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    val restoredFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    restoredFolder.isRecycled shouldBe false
  }

  test("Restoring an asset into a recycled folder should restore the full ancestor chain") {

    /**
     * Setup:
     *
     * A chain of nested folders A > B > C with an asset in C; deleting A recycles all three folders and the asset.
     *
     * Assertions:
     *
     * Restoring the asset brings back the asset and every folder on its path, not just the immediate one.
     */
    val folderA: Folder = testApp.service.folder.add("A")
    val folderB: Folder = testApp.service.folder.add(name = "B", parentId = folderA.id)
    val folderC: Folder = testApp.service.folder.add(name = "C", parentId = folderB.id)

    val asset: Asset = testContext.persistAsset(folder = Some(folderC))

    // Delete the top-level folder — cascades recycled flag to B, C and the asset
    testApp.service.library.deleteFolderById(folderA.persistedId)

    (testApp.service.folder.getById(folderA.persistedId): Folder).isRecycled shouldBe true
    (testApp.service.folder.getById(folderB.persistedId): Folder).isRecycled shouldBe true
    (testApp.service.folder.getById(folderC.persistedId): Folder).isRecycled shouldBe true

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    // All ancestors plus the immediate folder should be unrecycled
    (testApp.service.folder.getById(folderA.persistedId): Folder).isRecycled shouldBe false
    (testApp.service.folder.getById(folderB.persistedId): Folder).isRecycled shouldBe false
    (testApp.service.folder.getById(folderC.persistedId): Folder).isRecycled shouldBe false

    val restoredAsset: Asset = testApp.service.asset.getById(asset.persistedId)
    restoredAsset.isRecycled shouldBe false
  }

  test("Restoring a triage-origin asset brings it back to triage") {

    /**
     * Setup:
     *
     * One asset in triage (no folder), recycled and then restored.
     *
     * Assertions:
     *
     * The asset keeps its triage flag while recycled, and comes back to triage, still with no folder.
     */
    val asset: Asset = testContext.persistAsset(isTriaged = true)
    (testApp.service.asset.getById(asset.persistedId): Asset).isTriaged shouldBe true

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    val recycled: Asset = testApp.service.asset.getById(asset.persistedId)
    recycled.isRecycled shouldBe true
    recycled.isTriaged shouldBe true

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    val restored: Asset = testApp.service.asset.getById(asset.persistedId)
    restored.isRecycled shouldBe false
    restored.isTriaged shouldBe true
    restored.folderId shouldBe empty
  }

  test("Restoring triage-origin asset updates triage stats, not sorted stats") {

    /**
     * Setup:
     *
     * One asset in triage, recycled and then restored.
     *
     * Assertions:
     *
     * The asset moves from the triage count to the recycled count and back, and the sorted count stays at zero throughout.
     */
    val asset: Asset = testContext.persistAsset(isTriaged = true)
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
  }

  test("Restoring sorted asset updates sorted stats, not triage stats") {

    /**
     * Setup:
     *
     * One asset in a folder, recycled and then restored.
     *
     * Assertions:
     *
     * The asset moves from the sorted count to the recycled count and back, and the triage count stays at zero throughout.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
  }
}
