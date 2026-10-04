package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ empty, shouldBe }

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query

@DoNotDiscover class LibraryServiceRestoreTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Move recycled asset to folder") {
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
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))
    testApp.service.asset.query(new Query()).isEmpty shouldBe false
  }

  test("Restore an asset that was imported again") {
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

  test("Restore skips an asset whose content is live again and restores the rest") {
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
    val folder1: Folder = testApp.service.folder.add("folder1")
    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    // A two-element set keeps its insertion order: the asset is restored before the unknown ID fails
    intercept[NotFoundException] {
      testApp.service.library.restoreRecycledAssets(Set(asset.persistedId, "00000000-0000-0000-0000-000000000000"))
    }

    (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe true
    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
  }

  test("Restoring an asset into a recycled folder should restore the folder") {
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
