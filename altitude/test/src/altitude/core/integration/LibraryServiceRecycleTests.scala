package altitude.core.integration

import altitude.core.*
import altitude.core.models.*
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.util.Query
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

@DoNotDiscover class LibraryServiceRecycleTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Recycle multiple assets") {
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

  /** Just because an asset is recycled doesn't mean it can't be retrieved */
  test("Get recycled asset") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.asset.getById(asset.persistedId)
  }

  test("Recycle folder assets") {
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
}
