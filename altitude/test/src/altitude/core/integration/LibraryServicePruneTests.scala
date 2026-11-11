package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import java.nio.file.Files
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.*
import altitude.core.dao.jdbc.BaseDao
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

  test("Discarding an import leaves the unfinished import of another repository alone") {

    /**
     * Setup:
     *
     * Two repositories with one asset each, both flagged as not pipeline-processed, and the second repository's asset discarded
     * in the first repository's context.
     *
     * Assertions:
     *
     * Nothing is discarded: the second repository's asset is still there, dangling, with its file.
     */
    val unprocessed = Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)
    val firstRepo: Repository = testContext.repository

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)
    val secondAsset = testContext.persistAsset(repository = Some(secondRepo))
    testApp.service.asset.updateById(secondAsset.persistedId, unprocessed)

    switchContextRepo(firstRepo)
    testApp.service.library.discardImport(secondAsset)

    switchContextRepo(secondRepo)
    testApp.service.asset.getDanglingAssets.map(_.persistedId) shouldBe List(secondAsset.persistedId)
    Files.exists(testApp.service.fileStore.assetFile(secondAsset.persistedId)) shouldBe true
  }

  test("Pruning discards an import cut off before it completed: its rows, files and the face counts it added") {

    /**
     * Setup:
     *
     * `people/meme-ben2.png` imported, which starts a Person with one Face. Then two imports cut off before completing, each
     * persisted, indexed, stored, previewed and its faces recognized through the services: `people/meme-ben3.png`, which adds a
     * second Face to that Person, and `people/bullock.jpg`, which starts a Person of its own. Then the dangling assets pruned.
     *
     * Assertions:
     *
     * Neither cut-off asset is left, nor its file, its preview, its Faces or their files. The first Person is back to one Face,
     * the first import's, whose files remain; the Person the cut-off import started is gone. The stats, which count an asset only
     * once its import completes, are unchanged.
     */
    val complete = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/meme-ben2.png"))
    val (coverFace, person) = testApp.service.person.getAssetFacesWithPeople(complete.persistedId).head
    val statsBefore = storedStats

    val cutOff = List(danglingImport("people/meme-ben3.png"), danglingImport("people/bullock.jpg"))
    val cutOffFaces = cutOff.flatMap(asset => testApp.service.person.getAssetFacesWithPeople(asset.persistedId))
    testApp.service.person.getPersonById(person.persistedId).numOfFaces shouldBe 2
    val startedPerson = cutOffFaces.map(_._2).find(_.persistedId != person.persistedId).get

    testApp.service.library.pruneDanglingAssets()

    testApp.service.asset.getDanglingAssets shouldBe empty
    cutOff.foreach {
      asset =>
        testApp.service.asset.queryAll(new Query(Map(FieldConst.ID -> asset.persistedId))).total shouldBe 0
        Files.exists(testApp.service.fileStore.assetFile(asset.persistedId)) shouldBe false
        intercept[NotFoundException](testApp.service.asset.getPreview(asset.persistedId))
    }
    cutOffFaces.foreach {
      case (face, _) =>
        intercept[NotFoundException](testApp.service.person.getFaceById(face.persistedId))
        intercept[NotFoundException](testApp.service.fileStore.getDisplayFaceById(face.persistedId))
    }

    testApp.service.person.getPersonById(person.persistedId).numOfFaces shouldBe 1
    testApp.service.fileStore.getDisplayFaceById(coverFace.persistedId).data should not be empty
    intercept[NotFoundException](testApp.service.person.getPersonById(startedPerson.persistedId))
    storedStats shouldBe statsBefore
  }

  test("Discarding a cut-off import whose Face is an existing Person's cover gives them their best Face left") {

    /**
     * Setup:
     *
     * A Person with a Face at a detection score of 0.5 on a completed asset and one at 0.7 on an asset marked for purging. An
     * import cut off before it completed adds a Face of theirs at 0.9, which becomes their cover; then that import is discarded.
     *
     * Assertions:
     *
     * The Person stays, and their cover is their Face on the completed asset. The cut-off import's Face is gone, row and images.
     *
     * Edge cases:
     *
     * The Face on the asset marked for purging scores higher than the new cover, but is never picked.
     */
    val added = testApp.service.person.addPerson(Person())
    def person: Person = testApp.service.person.getPersonById(added.persistedId)

    val kept = testContext.addTestFace(person, testContext.insertAsset(), TestContext.unitVector(1), detectionScore = 0.5)
    val purging = testContext.insertAsset()
    testContext.addTestFace(person, purging, TestContext.unitVector(2), detectionScore = 0.7)
    testApp.service.library.recycleAssets(Set(purging.persistedId))
    testApp.service.asset.updateById(purging.persistedId, Map(FieldConst.Asset.IS_PURGED -> true))

    val cutOff = testApp.service.library.persistAndIndex(testContext.makeAsset())
    val cover =
      testContext.addTestFace(person, cutOff, TestContext.unitVector(3), detectionScore = 0.9, withFiles = true)
    testApp.service.person.setFaceAsCover(person, cover)

    testApp.service.library.discardImport(cutOff)

    person.coverFaceId shouldBe Some(kept.persistedId)
    intercept[NotFoundException](testApp.service.person.getFaceById(cover.persistedId))
    intercept[NotFoundException](testApp.service.fileStore.getDisplayFaceById(cover.persistedId))
  }

  /** An import cut off before it completed: persisted and indexed, its file stored, its preview made, its faces recognized */
  private def danglingImport(relPath: String): Asset = {
    val dataAsset = testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.getImportAsset(relPath))
    val persisted = testApp.service.library.persistAndIndex(dataAsset.asset.copy(id = Some(BaseDao.genId)))
    testApp.service.fileStore.addAsset(dataAsset.copy(asset = persisted))

    val stored = AssetWithData(persisted, testApp.service.fileStore.assetFile(persisted.persistedId))
    testApp.service.faceRecognition.recognizeAndStore(persisted, testApp.service.faceRecognition.detect(stored))
    testApp.service.asset.addPreview(stored)
    persisted
  }
}
