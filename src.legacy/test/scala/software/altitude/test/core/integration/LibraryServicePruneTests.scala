package software.altitude.test.core.integration
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core._
import software.altitude.core.models._
import software.altitude.core.util.Query
import software.altitude.core.util.SearchQuery
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class LibraryServicePruneTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Prune should remove all assets in undefined state") {
    val assetCount = 3
    for (_ <- 1 to assetCount)
      testContext.persistAsset()

    val assetQuery = new Query(Map(FieldConst.Asset.FOLDER_ID -> testContext.repository.rootFolderId))
    testApp.service.asset.queryAll(assetQuery).total shouldBe assetCount

    val assetSearchQuery = new SearchQuery(rpp = 3, page = 1)
    testApp.service.library.search(assetSearchQuery).total shouldBe assetCount

    // make all assets "dangling"
    val updateData = Map(
      FieldConst.Asset.IS_PIPELINE_PROCESSED -> false,
    )
    testApp.service.asset.updateByQuery(assetQuery, updateData)

    val danglingAssets: List[Asset] = testApp.service.asset.getDanglingAssets
    danglingAssets.length shouldBe assetCount

    // this will remove all assets in undefined state
    testApp.service.library.pruneDanglingAssets()

    // pruneDanglingAssets() method is a cross-repo operation, resetting the context
    // so we need to set it back to the test repo
    RequestContext.repository.value = Some(testContext.repository)

    testApp.service.asset.queryAll(assetQuery).total shouldBe 0
    // The items are still in the search index but not discoverable.
    // Not tidy but will do for now.
    testApp.service.library.search(assetSearchQuery).total shouldBe 0
  }
}
