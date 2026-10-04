package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.util.Query

@DoNotDiscover class AssetServiceTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("A Video's duration is stored and read back, and an image has none") {

    /**
     * Setup:
     *
     * Two assets added straight through the asset service, bypassing the pipeline: one with a duration of 123456789 ms, as a
     * video has, and one without.
     *
     * Assertions:
     *
     * The duration survives both a read by ID and a query, and the asset without one reads back with none on either path.
     */
    val video: Asset = testApp.service.asset.add(testContext.makeAsset().copy(durationMs = Some(123456789L)))
    val image: Asset = testApp.service.asset.add(testContext.makeAsset())

    testApp.service.asset.getById(video.persistedId).durationMs shouldBe Some(123456789L)
    testApp.service.asset.getById(image.persistedId).durationMs shouldBe None

    val queried = testApp.service.asset.query(new Query()).records.map(asset => asset.persistedId -> asset.durationMs).toMap
    queried(video.persistedId) shouldBe Some(123456789L)
    queried(image.persistedId) shouldBe None
  }

  test("Getting asset by invalid ID should raise NotFoundException") {

    /**
     * Setup:
     *
     * An asset ID that does not exist ("invalid").
     *
     * Assertions:
     *
     * Reading the asset fails as not found.
     */
    intercept[NotFoundException] {
      testApp.service.asset.getById("invalid")
    }
  }

  test("Getting preview by invalid asset ID should raise NotFoundException") {

    /**
     * Setup:
     *
     * An asset ID that does not exist ("invalid").
     *
     * Assertions:
     *
     * Reading its preview fails as not found.
     */
    intercept[NotFoundException] {
      testApp.service.asset.getPreview("invalid")
    }
  }

  test("Should be able to update 'isRecycled' property with 'updateById()'") {

    /**
     * Setup:
     *
     * One live asset, whose recycled flag is then set directly with updateById.
     *
     * Assertions:
     *
     * The flag is stored: the asset reads back as recycled.
     */
    val asset: Asset = testContext.persistAsset()
    asset.isRecycled shouldBe false

    testApp.service.asset.updateById(asset.persistedId, Map(FieldConst.Asset.IS_RECYCLED -> true))

    (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe true
  }

  test("Asset queries leave recycled assets out unless the recycled flag is queried directly") {

    /**
     * Setup:
     *
     * One live asset and one recycled asset.
     *
     * Assertions:
     *
     * A plain asset query finds the live asset alone, and a query of every asset filtered on the recycled flag finds the recycled
     * one alone.
     */
    val live = testContext.persistAsset()
    val recycled = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val notRecycled = testApp.service.asset.query(new Query()).records
    notRecycled.map(_.persistedId) shouldBe List(live.persistedId)

    val recycledOnly = testApp.service.asset.queryAll(new Query(params = Map(FieldConst.Asset.IS_RECYCLED -> true))).records
    recycledOnly.map(_.persistedId) shouldBe List(recycled.persistedId)
  }
}
