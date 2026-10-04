package altitude.core.integration

import altitude.test.TestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.empty
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Stats
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.util.Query

@DoNotDiscover class StatsServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val sixStats = List(
    Stats.SORTED_ASSETS,
    Stats.SORTED_BYTES,
    Stats.TRIAGE_ASSETS,
    Stats.TRIAGE_BYTES,
    Stats.RECYCLED_ASSETS,
    Stats.RECYCLED_BYTES)

  /** Sets every stored stat of the context repository to the value, behind the services' back */
  private def setEveryStat(value: Long): Unit =
    testApp.txManager.withTransaction {
      update("UPDATE stats SET dim_val = ? WHERE repository_id = ?", value, testContext.repository.persistedId)
    }

  /** An asset of the size, persisted and counted as an import does, without its file */
  private def importedAsset(sizeBytes: Long, isTriaged: Boolean = false): Asset = {
    val asset = testContext.makeAsset(isTriaged = isTriaged).copy(sizeBytes = sizeBytes)
    testApp.service.library.completeImport(testApp.service.library.persistAndIndex(asset))
  }

  /** Runs `f` on a thread of its own, which inherits the test's repository and account but not its transaction */
  private def onAnotherThread(f: => Unit): (Thread, AtomicReference[Option[Throwable]]) = {
    val failure = new AtomicReference[Option[Throwable]](None)
    val thread = new Thread(
      () =>
        try f
        catch { case ex: Throwable => failure.set(Some(ex)) })
    thread.start()
    (thread, failure)
  }

  /**
   * Runs `first` and `second` on threads of their own, each in its transaction, `first` holding its transaction open until
   * `second` waits for it: for a row lock on PostgreSQL, whose backend is identified by its process ID, and for the one write
   * connection on SQLite, whose waiter is the thread parked in the pool.
   */
  private def contend(first: => Unit, second: => Unit): Unit = {
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val secondBackend = new AtomicReference[Option[AnyRef]](None)

    val (firstThread, firstFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        first
        holding.countDown()
        release.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      holding.await(30, TimeUnit.SECONDS) shouldBe true

      val (secondThread, secondFailure) = onAnotherThread {
        testApp.txManager.withTransaction {
          if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
            secondBackend.set(Some(query("SELECT pg_backend_pid() AS pid").head("pid")))
          }
          second
        }
      }

      // pg_stat_activity is read once per transaction, so every poll is a transaction of its own
      def secondIsWaiting: Boolean = testApp.dataSourceType match {
        case Const.DbEngineName.POSTGRES =>
          secondBackend.get.exists(
            pid =>
              testApp.txManager.asReadOnly {
                query("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid).exists(_("wait_event_type") == "Lock")
              })
        case _ => secondThread.getState == Thread.State.TIMED_WAITING || secondThread.getState == Thread.State.WAITING
      }

      Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(20, Millis))) {
        secondIsWaiting shouldBe true
      }
      release.countDown()
      secondThread.join()
      secondFailure.get.foreach(throw _)
    } finally {
      release.countDown()
      firstThread.join()
    }
    firstFailure.get.foreach(throw _)
  }

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
     * Stat writes made outside any transaction: the triage count incremented by three and decremented by one, then a triaged,
     * recycled asset that was never persisted counted, and its restore applied.
     *
     * Assertions:
     *
     * Each write takes effect and is visible to the next read. The asset is counted as recycled, and the restore moves it back to
     * triage.
     */
    testApp.service.stats.incrementStat(Stats.TRIAGE_ASSETS, 3)
    testApp.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
    testApp.service.stats.getStats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 2

    val recycled = testContext.makeAsset(isTriaged = true, isRecycled = true)
    testApp.service.stats.addAsset(recycled)
    storedStats(Stats.RECYCLED_ASSETS) shouldBe 1

    testApp.service.stats.transition(before = List(recycled), after = List(recycled.copy(isRecycled = false)))
    storedStats(Stats.TRIAGE_ASSETS) shouldBe 3
    storedStats(Stats.RECYCLED_ASSETS) shouldBe 0
  }

  test("A write to a stat that has no row fails") {

    /**
     * Setup:
     *
     * A write to a dimension the repository has no stat row for.
     *
     * Assertions:
     *
     * The write fails rather than changing nothing.
     */
    intercept[ConstraintException] {
      testApp.service.stats.incrementStat("no_such_dimension")
    }
  }

  test("A write that would take a stat below zero fails and changes no stat") {

    /**
     * Setup:
     *
     * A triage count of one; then one write that adds a sorted asset and takes two triaged assets out.
     *
     * Assertions:
     *
     * The write fails as a whole: the triage count stays at one and the sorted count, written before it in dimension order, at
     * zero.
     */
    testApp.service.stats.incrementStat(Stats.TRIAGE_ASSETS)

    intercept[ConstraintException] {
      testApp.service.stats.adjust(Map(Stats.SORTED_ASSETS -> 1L, Stats.TRIAGE_ASSETS -> -2L))
    }

    storedStats(Stats.TRIAGE_ASSETS) shouldBe 1
    storedStats(Stats.SORTED_ASSETS) shouldBe 0
  }

  test("Purging the same recycled asset twice takes it out of the recycled stats once") {

    /**
     * Setup:
     *
     * One recycled asset, purged twice in one transaction, so the purge queue cannot delete its row in between: the second purge
     * finds the asset marked for purging.
     *
     * Assertions:
     *
     * The recycled count and bytes are back to zero, not below it.
     */
    val asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.txManager.withTransaction {
      testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
      testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
    }

    storedStats(Stats.RECYCLED_ASSETS) shouldBe 0
    storedStats(Stats.RECYCLED_BYTES) shouldBe 0
  }

  test("Emptying the trash after purging part of it leaves the recycled stats at zero") {

    /**
     * Setup:
     *
     * Two recycled assets; the first is purged, then the trash is emptied, in one transaction, so the first is still marked for
     * purging when the trash is emptied.
     *
     * Assertions:
     *
     * Emptying the trash succeeds and counts only the second asset out: the recycled count and bytes are zero.
     */
    val assets = (1 to 2).map(_ => testContext.persistAsset())
    testApp.service.library.recycleAssets(assets.map(_.persistedId).toSet)

    testApp.txManager.withTransaction {
      testApp.service.library.purgeSelectedAssets(Set(assets.head.persistedId))
      testApp.service.library.purgeRecycleBin()
    }

    storedStats(Stats.RECYCLED_ASSETS) shouldBe 0
    storedStats(Stats.RECYCLED_BYTES) shouldBe 0
  }

  test("Two requests restoring the same asset at once restore it once") {

    /**
     * Setup:
     *
     * One asset in a folder, recycled. Two threads restore it, the first holding its transaction open until the second waits for
     * it.
     *
     * Assertions:
     *
     * The asset is live and the stats moved once: one sorted asset and its bytes, nothing recycled.
     */
    val folder: Folder = testApp.service.folder.add("folder1")
    val asset = testContext.persistAsset(folder = Some(folder))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    contend(
      testApp.service.library.restoreRecycledAssets(Set(asset.persistedId)),
      testApp.service.library.restoreRecycledAssets(Set(asset.persistedId)))

    (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe false
    storedStats shouldBe Map(
      Stats.SORTED_ASSETS -> 1L,
      Stats.SORTED_BYTES -> asset.sizeBytes,
      Stats.TRIAGE_ASSETS -> 0L,
      Stats.TRIAGE_BYTES -> 0L,
      Stats.RECYCLED_ASSETS -> 0L,
      Stats.RECYCLED_BYTES -> 0L
    )
  }

  test("Two requests purging the same asset at once purge it once") {

    /**
     * Setup:
     *
     * Two recycled assets. Two threads purge the first, the first thread holding its transaction open until the second waits for
     * it.
     *
     * Assertions:
     *
     * The recycled stats moved once and hold the second asset only, and the purge queue deletes the first asset's row.
     */
    val assets = (1 to 2).map(_ => testContext.persistAsset())
    testApp.service.library.recycleAssets(assets.map(_.persistedId).toSet)
    val purged = assets.head

    contend(
      testApp.service.library.purgeSelectedAssets(Set(purged.persistedId)),
      testApp.service.library.purgeSelectedAssets(Set(purged.persistedId)))

    storedStats(Stats.RECYCLED_ASSETS) shouldBe 1
    storedStats(Stats.RECYCLED_BYTES) shouldBe assets.last.sizeBytes
    Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(100, Millis))) {
      intercept[NotFoundException](testApp.service.asset.getById(purged.persistedId))
    }
  }

  test("Reconciling sets every stat to what the assets are and reports the wrong ones") {

    /**
     * Setup:
     *
     * Assets of distinct sizes in every state: two sorted (100 and 200 bytes), one triaged (20), two recycled, one from a folder
     * (3) and one from triage (4), one recycled and marked for purging (5,000), and one whose import never completed (70,000).
     * Every stored stat is then set to 999 directly.
     *
     * Assertions:
     *
     * Reconciling reports all six stats with their old value, 999, and sets them to the assets that count: the purge-pending and
     * the unfinished asset count nowhere, and an asset recycled from triage counts as recycled.
     */
    importedAsset(sizeBytes = 100)
    importedAsset(sizeBytes = 200)
    importedAsset(sizeBytes = 20, isTriaged = true)
    val recycled = List(importedAsset(sizeBytes = 3), importedAsset(sizeBytes = 4, isTriaged = true))
    val purgePending = importedAsset(sizeBytes = 5000)
    testApp.service.library.recycleAssets((purgePending :: recycled).map(_.persistedId).toSet)
    testApp.service.asset.updateById(purgePending.persistedId, Map(FieldConst.Asset.IS_PURGED -> true))
    testApp.service.library.persistAndIndex(testContext.makeAsset().copy(sizeBytes = 70000))
    setEveryStat(999)

    testApp.service.stats.reconcile() shouldBe sixStats.map(_ -> 999L).toMap

    storedStats shouldBe Map(
      Stats.SORTED_ASSETS -> 2L,
      Stats.SORTED_BYTES -> 300L,
      Stats.TRIAGE_ASSETS -> 1L,
      Stats.TRIAGE_BYTES -> 20L,
      Stats.RECYCLED_ASSETS -> 2L,
      Stats.RECYCLED_BYTES -> 7L)
  }

  test("Reconciling correct stats changes and reports nothing") {

    /**
     * Setup:
     *
     * A sorted, a triaged and a recycled asset, counted by the library operations themselves.
     *
     * Assertions:
     *
     * Reconciling reports nothing and leaves the stats as they were.
     */
    importedAsset(sizeBytes = 100)
    importedAsset(sizeBytes = 20, isTriaged = true)
    testApp.service.library.recycleAssets(Set(importedAsset(sizeBytes = 3).persistedId))
    val before = storedStats

    testApp.service.stats.reconcile() shouldBe empty

    storedStats shouldBe before
  }

  test("Reconciling a repository with no assets sets every stat to zero") {

    /**
     * Setup:
     *
     * A repository with no assets, its every stored stat set to 7 directly.
     *
     * Assertions:
     *
     * Reconciling reports all six stats and sets each to zero.
     */
    setEveryStat(7)

    testApp.service.stats.reconcile().keySet shouldBe sixStats.toSet

    storedStats.values.toSet shouldBe Set(0L)
  }
}
