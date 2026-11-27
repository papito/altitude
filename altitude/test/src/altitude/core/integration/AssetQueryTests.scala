package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

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
     * A query with no filter returns no records and a total of none.
     */
    val results = testApp.service.library.query(new Query())
    results.records.length shouldBe 0
    results.total shouldBe 0
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

  test("The repository view leaves triaged assets out unless asked, and the trash tells them apart") {

    /**
     * Setup:
     *
     * An asset in the root folder, a triaged asset, two recycled assets, one of them recycled from triage, and an album holding
     * the sorted and the triaged asset; each view's scope parsed with and without the `triaged` parameter.
     *
     * Assertions:
     *
     * The default view without the parameter, or with `triaged=false`, holds the sorted asset alone, and with `triaged=true`
     * holds the sorted and the triaged asset. An album scope follows the same rule. The trash view with `triaged=true` holds the
     * asset recycled from triage alone, and with `triaged=false` the other recycled asset alone.
     *
     * Edge cases:
     *
     * The triage view with `triaged=false` is a contradiction the parser refuses, naming the view; with `triaged=true` it is the
     * triage view.
     */
    val sorted = testContext.persistAsset()
    val triaged = testContext.persistAsset(isTriaged = true)
    val recycled = testContext.persistAsset()
    val recycledFromTriage = testContext.persistAsset(isTriaged = true)
    testApp.service.library.recycleAssets(Set(recycled.persistedId, recycledFromTriage.persistedId))
    val album = testApp.service.album.add("Trip")
    testApp.service.album.addAssets(album.persistedId, Set(sorted.persistedId, triaged.persistedId))

    viewAssetIds(Const.Search.View.DEFAULT) shouldBe Set(sorted.persistedId)
    viewAssetIds(Const.Search.View.DEFAULT, triaged = Some(false)) shouldBe Set(sorted.persistedId)
    viewAssetIds(Const.Search.View.DEFAULT, triaged = Some(true)) shouldBe Set(sorted.persistedId, triaged.persistedId)
    viewAssetIds(Const.Search.View.DEFAULT, albumId = Some(album.persistedId)) shouldBe Set(sorted.persistedId)
    viewAssetIds(Const.Search.View.DEFAULT, triaged = Some(true), albumId = Some(album.persistedId)) shouldBe
      Set(sorted.persistedId, triaged.persistedId)
    viewAssetIds(Const.Search.View.TRASHBIN, triaged = Some(true)) shouldBe Set(recycledFromTriage.persistedId)
    viewAssetIds(Const.Search.View.TRASHBIN, triaged = Some(false)) shouldBe Set(recycled.persistedId)
    viewAssetIds(Const.Search.View.TRIAGE, triaged = Some(true)) shouldBe Set(triaged.persistedId)

    val refused =
      SearchRequestParser.parse(SearchRequestParser.Params(view = Const.Search.View.TRIAGE, triaged = Some(false)))
    refused.left.toOption.value should include("triage")
  }

  /** The IDs of the assets a search in the view's scope returns, as the search results route parses the view */
  private def viewAssetIds(view: String, triaged: Option[Boolean] = None, albumId: Option[String] = None): Set[String] =
    val params = SearchRequestParser.Params(view = view, albumId = albumId, triaged = triaged)
    val scope = SearchRequestParser.parse(params).toOption.value
    testApp.service.library.search(scope.query(rpp = 10)).records.map(_.persistedId).toSet
}
