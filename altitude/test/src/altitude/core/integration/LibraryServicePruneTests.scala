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
import altitude.core.util.Util

@DoNotDiscover class LibraryServicePruneTests(override val testApp: Altitude) extends IntegrationTestCore {
  test("Prune should remove all assets in undefined state") {

    /**
     * Setup:
     *
     * Three imported assets flagged as not pipeline-processed, as an unfinished import leaves them.
     *
     * Assertions:
     *
     * All three are dangling, and pruning removes them from queries, from search and their search documents with them.
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
     * A dangling asset in a second repository, discarded from the first repository's context.
     *
     * Assertions:
     *
     * Nothing is discarded: the asset is still there, dangling, with its file.
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
     * A completed import that starts a Person, then two imports cut off before completing: one adds a second Face to that Person,
     * the other starts a Person of its own. Then the dangling assets are pruned.
     *
     * Assertions:
     *
     * Neither cut-off asset is left, nor its file, preview, Faces or face files. The first Person is back to one Face, whose
     * files remain, and the Person the cut-off import started is gone. The stats, which count an asset only once its import
     * completes, are unchanged.
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
     * A Person with a Face scored 0.5 on a completed asset and one scored 0.7 on an asset marked for purging. A cut-off import
     * adds their cover Face, scored 0.9, and is then discarded.
     *
     * Assertions:
     *
     * The Person stays with the completed asset's Face as cover. The cut-off Face is gone, row and images.
     *
     * Edge cases:
     *
     * The higher-scoring Face on the asset marked for purging is never picked.
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
    testApp.service.person.setFaceAsCover(added.persistedId, cover.persistedId)

    testApp.service.library.discardImport(cutOff)

    person.coverFaceId shouldBe Some(kept.persistedId)
    intercept[NotFoundException](testApp.service.person.getFaceById(cover.persistedId))
    intercept[NotFoundException](testApp.service.fileStore.getDisplayFaceById(cover.persistedId))
  }

  test("A discard, a rename of the importer and a recycle of an asset sharing a Person with it at once all commit") {

    /**
     * Setup:
     *
     * A User who imported two assets, the one of the lower ID cut off before its completion, and a Person with a Face on each. A
     * third transaction holds the Library's stat rows; then a recycle of the completed asset starts and waits on them; then an
     * Admin's rename of the User, which rewrites both importer records in ID order, starts and waits on the recycle; then the
     * discard of the cut-off import starts and waits; then the stat rows are let go (`whileHolding`).
     *
     * Assertions:
     *
     * None fails. The cut-off asset is gone; the other is recycled, its importer record naming the User as renamed; the Person,
     * who still has a Face, stays.
     *
     * Edge cases:
     *
     * A discard that locks the Person before the cut-off asset would hold the Person while waiting on the rename, which holds the
     * cut-off asset and waits on the recycle, which waits on the Person to take the recycled asset from its count: a deadlock of
     * three.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val users = testApp.service.user
    val admin = testContext.persistAdmin()
    val grants = Map(testContext.repository.persistedId -> Permissions.all)
    val importer = users.create(admin.persistedId, testContext.makeUser(), "password 1", grants)
    val List(lowerId, higherId) = List.fill(2)(BaseDao.genId).sorted
    val cutOff =
      testApp.service.library.persistAndIndex(testContext.makeAsset(user = Some(importer)).copy(id = Some(lowerId)))
    val other = testContext.insertAsset(testContext.makeAsset(user = Some(importer)).copy(id = Some(higherId)))
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFace(person, cutOff, TestContext.unitVector(1))
    testContext.addTestFace(testApp.service.person.getById(person.persistedId), other, TestContext.unitVector(2))
    val renamed = importer.copy(name = s"Renamed ${Util.randomStr(6)}")
    val opened = users.openForEdit(importer.persistedId).state

    whileHolding(
      query("SELECT dimension FROM stats WHERE repository_id = ? FOR UPDATE", testContext.repository.persistedId): Unit)(
      () => testApp.service.library.recycleAssets(Set(other.persistedId)),
      () => users.update(admin.persistedId, renamed, grants, opened): Unit,
      () => testApp.service.library.discardImport(cutOff)
    )

    intercept[NotFoundException](testApp.service.asset.getById(cutOff.persistedId))
    val kept = testApp.service.asset.getById(other.persistedId)
    (kept.isRecycled, kept.originalOwner.name) shouldBe (true, renamed.name)
    testApp.service.person.getById(person.persistedId).persistedId shouldBe person.persistedId
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
