package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query
import altitude.core.util.SearchQuery

@DoNotDiscover class LibraryServicePruneTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Prune should remove all assets in undefined state") {

    /**
     * Setup:
     *
     * Three imported assets in the root folder, all then flagged as not pipeline-processed, the state an import that never
     * finished leaves an asset in.
     *
     * Assertions:
     *
     * The three are found as dangling and pruned, after which none of them is left to query or to search, and their search
     * documents are deleted with them.
     */
    val assetCount = 3
    for (_ <- 1 to assetCount)
      testContext.persistAsset()

    val assetQuery = new Query(Map(FieldConst.Asset.FOLDER_ID -> testContext.repository.rootFolderId))
    testApp.service.asset.queryAll(assetQuery).total shouldBe assetCount

    val assetSearchQuery = new SearchQuery(rpp = 3)
    testApp.service.library.search(assetSearchQuery).total shouldBe Some(assetCount)

    def searchDocumentCount: Int = testApp.txManager.asReadOnly {
      query("SELECT count(*) AS n FROM search_document WHERE repository_id = ?", testContext.repository.persistedId)
        .head("n")
        .toString
        .toInt
    }
    searchDocumentCount shouldBe assetCount

    // make all assets "dangling"
    val updateData = Map(
      FieldConst.Asset.IS_PIPELINE_PROCESSED -> false
    )
    testApp.service.asset.updateByQuery(assetQuery, updateData)

    val danglingAssets: List[Asset] = testApp.service.asset.getDanglingAssets
    danglingAssets.length shouldBe assetCount

    // this will remove all assets in undefined state
    testApp.service.library.pruneDanglingAssets()

    testApp.service.asset.queryAll(assetQuery).total shouldBe 0
    testApp.service.library.search(assetSearchQuery).total shouldBe Some(0)
    // A search document cascades with its asset
    searchDocumentCount shouldBe 0
  }

  test("Pruning a repository leaves the unfinished imports of another alone") {

    /**
     * Setup:
     *
     * Two repositories with one asset each, both flagged as not pipeline-processed, and the first repository's assets pruned.
     *
     * Assertions:
     *
     * The first repository's asset is gone and the second repository's is still there.
     */
    val unprocessed = Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)
    val firstRepo: Repository = testContext.repository
    val firstAsset = testContext.persistAsset()
    testApp.service.asset.updateById(firstAsset.persistedId, unprocessed)

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)
    val secondAsset = testContext.persistAsset(repository = Some(secondRepo))
    testApp.service.asset.updateById(secondAsset.persistedId, unprocessed)

    switchContextRepo(firstRepo)
    testApp.service.asset.pruneDanglingAssets()

    testApp.service.asset.getDanglingAssets shouldBe empty
    switchContextRepo(secondRepo)
    testApp.service.asset.getDanglingAssets.map(_.persistedId) shouldBe List(secondAsset.persistedId)
  }
}
