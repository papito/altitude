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
    val persistedAsset: Asset = testApp.service.library.addAsset(dataAsset)

    // recycle the asset
    testApp.service.library.recycleAssets(Set(persistedAsset.persistedId))

    // import a new copy of it (should be allowed)
    testApp.service.library.addAsset(dataAsset)

    // now restore the previously deleted copy into itself
    intercept[DuplicateException] {
      testApp.service.library.restoreRecycledAssets(Set(persistedAsset.persistedId))
    }
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
