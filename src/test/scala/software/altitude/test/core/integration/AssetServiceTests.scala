package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.NotFoundException
import software.altitude.core.models.Asset
import software.altitude.core.util.Query
import software.altitude.core.{Const => C}
import software.altitude.test.core.IntegrationTestCore


@DoNotDiscover class AssetServiceTests (val config: Map[String, Any]) extends IntegrationTestCore {
  test("Getting asset by invalid ID should raise NotFoundException") {
    intercept[NotFoundException] {
      altitude.service.library.getById("invalid")
    }
  }

  test("Getting preview by invalid asset ID should raise NotFoundException") {
    intercept[NotFoundException] {
      altitude.service.library.getPreview("invalid")
    }
  }

  test("Should be able to update 'isRecycled' property with 'updateById()'") {
    val asset: Asset = testContext.persistAsset()
    asset.isRecycled shouldBe false

    val updateAsset: Asset = asset.modify(C.Asset.IS_RECYCLED -> true)

    altitude.service.asset.updateById(
      asset.id.get, updateAsset,
      fields = List(C.Asset.IS_RECYCLED))

    (altitude.service.library.getById(asset.id.get): Asset).isRecycled shouldBe true
  }

  test("Should be able to query by the recycled property") {
    testContext.persistAsset()

    val q = new Query(params = Map(C.Asset.IS_RECYCLED -> false))
    val result = altitude.service.asset.query(q)

    result.total shouldBe 1
  }
}
