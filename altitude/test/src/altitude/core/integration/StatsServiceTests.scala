package altitude.core.integration

import altitude.test.TestContext
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Stats
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.util.Query

@DoNotDiscover class StatsServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Test totals") {

    /**
     * Setup:
     *
     * In the common repository: an asset in a folder, a triaged asset, and two assets recycled right after import, one from the
     * folder and one from the root folder. The triaged asset is then moved into the folder, and last a second, empty repository
     * becomes the current one.
     *
     * Assertions:
     *
     * The sorted, triage, recycled and total counts follow each step, with the bytes of each at the count times the fixture's
     * size, and the second repository's stats are all zero, since stats are kept per repository.
     */
    // create an asset in a folder
    val folder1: Folder = testApp.service.folder.add("folder1")

    testContext.persistAsset(folder = Some(folder1))

    // create a triaged asset
    val triagedAsset: Asset = testContext.persistAsset(isTriaged = true)
    testApp.service.stats.getStats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1

    // create an asset and delete it
    val assetToDelete1: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(assetToDelete1.persistedId))
    // ditto
    val assetToDelete2: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(assetToDelete2.persistedId))

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 1
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * TestContext.ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * TestContext.ASSET_SIZE
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1
    stats.getStatValue(Stats.TOTAL_ASSETS) shouldBe 4
    stats.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats.getStatValue(Stats.TOTAL_ASSETS) * TestContext.ASSET_SIZE

    testApp.service.library.moveAssetsToFolder(Set(triagedAsset.persistedId), folder1.persistedId)

    val stats2 = testApp.service.stats.getStats
    stats2.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats2.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats2.getStatValue(Stats.SORTED_ASSETS) * TestContext.ASSET_SIZE
    stats2.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats2.getStatValue(Stats.TRIAGE_BYTES) shouldBe
      stats2.getStatValue(Stats.TRIAGE_ASSETS) * TestContext.ASSET_SIZE
    stats2.getStatValue(Stats.TOTAL_ASSETS) shouldBe 4
    stats2.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats2.getStatValue(Stats.TOTAL_ASSETS) * TestContext.ASSET_SIZE

    // SECOND REPO
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)

    val stats3 = testApp.service.stats.getStats

    stats3.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats3.getStatValue(Stats.SORTED_ASSETS) * TestContext.ASSET_SIZE
    stats3.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.TRIAGE_BYTES) shouldBe
      stats3.getStatValue(Stats.TRIAGE_ASSETS) * TestContext.ASSET_SIZE
    stats3.getStatValue(Stats.TOTAL_ASSETS) shouldBe 0
    stats3.getStatValue(Stats.TOTAL_BYTES) shouldBe
      stats3.getStatValue(Stats.TOTAL_ASSETS) * TestContext.ASSET_SIZE
  }

  test("Recycle multiple assets") {

    /**
     * Setup:
     *
     * Two triaged assets and two in a folder, then every asset of the repository recycled in one call.
     *
     * Assertions:
     *
     * Before the recycle the sorted and triage counts reflect the imports; after it nothing is left sorted and all four assets
     * are counted as recycled, bytes included.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    (1 to 2).foreach {
      _ =>
        testContext.persistAsset(isTriaged = true)
        testContext.persistAsset(folder = Some(folder1))
    }

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * TestContext.ASSET_SIZE
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 2

    val all: List[Asset] = testApp.service.asset.query(new Query()).records

    testApp.service.library.recycleAssets(all.map(_.persistedId).toSet)

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 4
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * TestContext.ASSET_SIZE
  }

  test("Recycle triaged assets") {

    /**
     * Setup:
     *
     * Five triaged assets, one of which is then recycled.
     *
     * Assertions:
     *
     * The recycled asset moves from the triage count to the recycled count.
     */
    val total = 5
    val triagedAssets = (1 to total).foldLeft(List[Asset]()) {
      (acc, _) =>
        val asset = testContext.persistAsset(isTriaged = true)
        acc :+ asset
    }

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe triagedAssets.length

    testApp.service.library.recycleAssets(Set(triagedAssets.head.persistedId))

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe triagedAssets.length - 1
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
  }

  test("Recycling already recycled asset should do nothing") {

    /**
     * Setup:
     *
     * Three assets in the root folder, the first of which is recycled twice.
     *
     * Assertions:
     *
     * The second recycle changes nothing: the asset leaves the sorted count and is counted as recycled only once.
     */
    val total = 3
    val assets = (1 to total).foldLeft(List[Asset]())((acc, _) => acc :+ testContext.persistAsset())

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe assets.length

    (1 to 2).foreach(_ => testApp.service.library.recycleAssets(Set(assets.head.persistedId)))

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe assets.length - 1
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 1
  }

  test("Recycle a folder") {

    /**
     * Setup:
     *
     * Two folders with two assets each, then the first folder deleted.
     *
     * Assertions:
     *
     * Deleting a folder recycles its assets: they leave the sorted count and are counted as recycled, bytes included, while the
     * other folder's assets stay sorted.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val folder2: Folder = testApp.service.folder.add("folder2")

    (1 to 2).foreach {
      _ =>
        testContext.persistAsset(folder = Some(folder1))
        testContext.persistAsset(folder = Some(folder2))
    }

    var stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 4

    testApp.service.library.deleteFolderById(folder1.persistedId)

    stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.SORTED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.SORTED_BYTES) shouldBe
      stats.getStatValue(Stats.SORTED_ASSETS) * TestContext.ASSET_SIZE
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe
      stats.getStatValue(Stats.RECYCLED_ASSETS) * TestContext.ASSET_SIZE
  }

  test("Purging the recycle bin should correctly update the recycle stats (to zero)") {

    /**
     * Setup:
     *
     * Five assets imported through the import pipeline in one stream, all of them recycled, then the recycle bin purged.
     *
     * Assertions:
     *
     * All five imports succeed, and once the bin is purged, the recycled count and bytes are back to zero.
     */
    val batchSize = 5
    val dataAssets = (1 to batchSize).map(_ => testContext.makeAssetWithData())

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => dataAssets.iterator).map((_, pipelineContext))
    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())
    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes.count(_._1.isLeft) shouldBe batchSize

    val allAssets: List[Asset] = testApp.service.asset.query(new Query()).records

    val allAssetIds = allAssets.map(_.persistedId).toSet
    testApp.service.library.recycleAssets(allAssetIds)

    // the stats update operation is performed synchronously, so this is fine
    testApp.service.library.purgeRecycleBin()

    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    stats.getStatValue(Stats.RECYCLED_BYTES) shouldBe 0
  }

  /** Folder counts have been removed - this needs to be re-engineered. Left here for reference. */
  /*
  test("Test move recycled asset to new folder") {
    var folder1: Folder = testApp.service.folder.add("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1

    testApp.service.library.recycleAsset(asset.persistedId)

    var folder2: Folder = testApp.service.folder.add("folder2")

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
    var folder1: Folder = testApp.service.folder.add("folder1")

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
    var folder1: Folder = testApp.service.folder.add("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    val trashed: Asset = testApp.service.library.recycleAsset(asset.persistedId)
    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 0

    testApp.service.library.restoreRecycledAssets(Set(trashed.persistedId))

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfAssets shouldBe 1
  }
   */

  test("Stat writes open their own transaction") {

    /**
     * Setup:
     *
     * Stat writes made outside any transaction: the triage count incremented by three and decremented by one, then the stats of a
     * restore applied for a triaged, recycled asset that was never persisted.
     *
     * Assertions:
     *
     * Each write takes effect and is visible to the next read. The restore moves the asset from recycled back to triage, which
     * takes the recycled count below zero, since nothing was recycled before.
     */
    testApp.service.stats.incrementStat(Stats.TRIAGE_ASSETS, 3)
    testApp.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
    testApp.service.stats.getStats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 2

    val recycled = testContext.makeAsset(isTriaged = true, isRecycled = true)
    testApp.service.stats.restoreAsset(recycled)
    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 3
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe -1
  }
}
