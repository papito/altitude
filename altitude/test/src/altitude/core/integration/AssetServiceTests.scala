package altitude.core.integration

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.util.Query
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

@DoNotDiscover class AssetServiceTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Getting asset by invalid ID should raise NotFoundException") {
    intercept[NotFoundException] {
      testApp.service.asset.getById("invalid")
    }
  }

  test("Getting preview by invalid asset ID should raise NotFoundException") {
    intercept[NotFoundException] {
      testApp.service.asset.getPreview("invalid")
    }
  }

  test("Should be able to update 'isRecycled' property with 'updateById()'") {
    val asset: Asset = testContext.persistAsset()
    asset.isRecycled shouldBe false

    testApp.service.asset.updateById(asset.persistedId, Map(FieldConst.Asset.IS_RECYCLED -> true))

    (testApp.service.asset.getById(asset.persistedId): Asset).isRecycled shouldBe true
  }

  test("Should be able to query by the recycled property") {
    testContext.persistAsset()

    val q = new Query(params = Map(FieldConst.Asset.IS_RECYCLED -> false))
    val result = testApp.service.asset.query(q)

    result.total shouldBe 1
  }
}
