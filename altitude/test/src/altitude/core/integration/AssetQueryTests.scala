package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.routes.SearchRequestParser
import altitude.core.util.Query

@DoNotDiscover class AssetQueryTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Empty search") {

    /**
     * Setup:
     *
     * A repository with no assets.
     *
     * Assertions:
     *
     * A query with no filter returns no records and no pages.
     */
    val results = testApp.service.library.query(new Query())
    results.records.length shouldBe 0
    results.totalPages shouldBe 0
  }

  test("Search all") {

    /**
     * Setup:
     *
     * One asset in the root folder.
     *
     * Assertions:
     *
     * A query with no filter returns it.
     */
    testContext.persistAsset()

    val assets = testApp.service.library.query(new Query()).records
    assets.length shouldBe 1
  }

  test("The triage view holds the triaged assets that are not recycled") {

    /**
     * Setup:
     *
     * An asset in the root folder and two triaged assets, one of them recycled, searched in the scope the triage view parses to.
     *
     * Assertions:
     *
     * The triage view returns the triaged asset still in the library, alone.
     *
     * Edge cases:
     *
     * Recycling leaves an asset's triage flag set, so only the view's scope keeps the recycled one out.
     */
    testContext.persistAsset()
    val triaged = testContext.persistAsset(isTriaged = true)
    val recycled = testContext.persistAsset(isTriaged = true)
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    viewAssetIds(Const.Search.View.TRIAGE) shouldBe Set(triaged.persistedId)
  }

  test("The trash view holds the recycled assets not yet marked for purging") {

    /**
     * Setup:
     *
     * An asset in the root folder and two recycled assets, one of them then marked for purging, searched in the scope the trash
     * view parses to.
     *
     * Assertions:
     *
     * The trash view returns the recycled asset that is not being purged, alone.
     *
     * Edge cases:
     *
     * An asset marked for purging is still recycled until the purge deletes it, and leaves the trash view at once.
     */
    testContext.persistAsset()
    val recycled = testContext.persistAsset()
    val purging = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId, purging.persistedId))
    testApp.service.asset.updateById(purging.persistedId, Map(FieldConst.Asset.IS_PURGED -> true))

    viewAssetIds(Const.Search.View.TRASHBIN) shouldBe Set(recycled.persistedId)
  }

  test("Pagination") {

    /**
     * Setup:
     *
     * Six assets in the root folder, queried two to a page, then six and twenty to a page.
     *
     * Assertions:
     *
     * Each page holds as many records as fit on it and every page reports the full total and page count, which is three at two to
     * a page and one when a page holds them all.
     *
     * Edge cases:
     *
     * A page past the last one comes back with no records, and with a total and page count of zero.
     */
    (1 to 6).foreach(n => testContext.persistAsset())

    val q = new Query(rpp = 2, page = 1)
    val results = testApp.service.library.query(q)
    results.total shouldBe 6
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true
    results.totalPages shouldBe 3

    val q2 = new Query(rpp = 2, page = 2)
    val results2 = testApp.service.library.query(q2)
    results2.total shouldBe 6
    results2.records.length shouldBe 2
    results2.totalPages shouldBe 3

    val q3 = new Query(rpp = 2, page = 3)
    val results3 = testApp.service.library.query(q3)
    results3.total shouldBe 6
    results3.records.length shouldBe 2
    results3.totalPages shouldBe 3

    // page too far
    val q4 = new Query(rpp = 2, page = 4)
    val results4 = testApp.service.library.query(q4)
    results4.total shouldBe 0
    results4.records.length shouldBe 0
    results4.totalPages shouldBe 0

    val q5 = new Query(rpp = 6, page = 1)
    val results5 = testApp.service.library.query(q5)
    results5.total shouldBe 6
    results5.records.length shouldBe 6
    results5.totalPages shouldBe 1

    val q6 = new Query(rpp = 20, page = 1)
    val results6 = testApp.service.library.query(q6)
    results6.total shouldBe 6
    results6.records.length shouldBe 6
    results6.totalPages shouldBe 1
  }

  /** The IDs of the assets a search in the view's scope returns, as the search results route parses the view */
  private def viewAssetIds(view: String): Set[String] =
    val scope = SearchRequestParser.parse(view, None, None, None, None, None, None).toOption.value
    testApp.service.library.search(scope.query(rpp = 10)).records.map(_.persistedId).toSet
}
