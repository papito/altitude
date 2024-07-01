package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.models.Asset
import software.altitude.core.models.Folder
import software.altitude.core.models.Stats
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class StatsServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  val ASSET_SIZE = 1000

  test("Test totals (simple cases)") {
    // create an asset in a folder
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    testContext.persistAsset(folder = Some(folder1))

    // create a triaged asset
    val triagedAssetModel = testContext.makeAsset().copy(isTriaged = true)
    val triagedAsset: Asset = testContext.persistAsset(Some(triagedAssetModel))
    testApp.service.stats.getStats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1

    // create an asset and delete it
    val assetToDelete1: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAsset(assetToDelete1.persistedId)
    // ditto
    val assetToDelete2: Asset = testContext.persistAsset()
    testApp.service.library.recycleAsset(assetToDelete2.persistedId)

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1
    stats.getStatValue(Stats.TOTAL_ASSETS) shouldBe 4
    stats.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats.getStatValue(Stats.TOTAL_ASSETS) * ASSET_SIZE

    testApp.service.library.moveAssetToFolder(triagedAsset.persistedId, folder1.persistedId)

    val stats2 = testApp.service.stats.getStats
    stats2.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats2.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats.getStatValue(Stats.TRIAGE_BYTES) shouldBe
      stats.getStatValue(Stats.TRIAGE_ASSETS) * ASSET_SIZE
    stats2.getStatValue(Stats.TOTAL_ASSETS) shouldBe 4
    stats.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats.getStatValue(Stats.TOTAL_ASSETS) * ASSET_SIZE

    // SECOND REPO
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)

    val stats3 = testApp.service.stats.getStats

    stats3.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats3.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats2.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.TRIAGE_BYTES) shouldBe
      stats3.getStatValue(Stats.TRIAGE_ASSETS) * ASSET_SIZE
    stats3.getStatValue(Stats.TOTAL_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats3.getStatValue(Stats.TOTAL_ASSETS) * ASSET_SIZE
  }

  test("Test move recycled asset to new folder") {
    var folder1: Folder = testApp.service.library.addFolder("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1

    testApp.service.library.recycleAsset(asset.persistedId)

    var folder2: Folder = testApp.service.library.addFolder("folder2")

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder2.persistedId)

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 0

    folder2 = testApp.service.folder.getById(folder2.persistedId)
    folder2.numOfAssets shouldBe 1

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
  }

  test("Test move recycled asset to original folder") {
    var folder1: Folder = testApp.service.library.addFolder("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1

    testApp.service.library.recycleAsset(asset.persistedId)
    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 0

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
  }

  test("Restore recycled asset to original folder") {
    var folder1: Folder = testApp.service.library.addFolder("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    val trashed: Asset = testApp.service.library.recycleAsset(asset.persistedId)
    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 0

    testApp.service.library.restoreRecycledAsset(trashed.persistedId)

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1
  }

  test("Recycle multiple assets") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    1 to 2 foreach { _ =>
      val triagedAssetModel = testContext.makeAsset().copy(isTriaged = true)
      testContext.persistAsset(Some(triagedAssetModel))
      testContext.persistAsset(folder = Some(folder1))
    }

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 2

    val all: List[Asset] = testApp.service.asset.query(new Query()).records.map(Asset.fromJson)

    testApp.service.library.recycleAssets(all.map(_.persistedId).toSet)

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 4
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * ASSET_SIZE
  }

  test("Recycle a folder") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")
    val folder2: Folder = testApp.service.library.addFolder("folder2")

    1 to 2 foreach { _ =>
      testContext.persistAsset(folder = Some(folder1))
      testContext.persistAsset(folder = Some(folder2))
    }

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 4

    testApp.service.library.deleteFolderById(folder1.persistedId)

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * ASSET_SIZE
  }
}
