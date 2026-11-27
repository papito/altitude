package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash
import altitude.core.models.Repository
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

  test("Completing an asset that is not in the Library is a not-found error, not a silent no-op") {

    /**
     * Setup:
     *
     * An asset never stored, and an asset of another Library, each completed as an import completes them.
     *
     * Assertions:
     *
     * Each completion fails with `NotFoundException`, as a write by an unknown ID does, rather than answer the asset as
     * completed; the other Library's asset stays incomplete.
     */
    val repository: Repository = testContext.repository
    val foreignRepository: Repository = testContext.persistRepository()
    switchContextRepo(foreignRepository)
    val foreign: Asset = testApp.service.asset.add(testContext.makeAsset(repository = Some(foreignRepository)))
    switchContextRepo(repository)

    intercept[NotFoundException](
      testApp.service.asset.markAsCompleted(testContext.makeAsset(repository = Some(repository)).copy(id = Some(BaseDao.genId))))
    intercept[NotFoundException](testApp.service.asset.markAsCompleted(foreign))
    switchContextRepo(foreignRepository)
    testApp.service.asset.getById(foreign.persistedId).isPipelineProcessed shouldBe false
  }

  test(
    "Image quality, the perceptual hash and the Sequence are stored on completion and read back, and an unmeasured asset has none") {

    /**
     * Setup:
     *
     * Two assets added through the asset service and completed as an import completes them: a triaged one (only a triaged row
     * takes a Sequence) carrying an Image quality and a perceptual hash with the high bit set, then put in a Sequence, and one
     * carrying none of them.
     *
     * Assertions:
     *
     * The metrics, the hash and the Sequence survive a read by ID, a query and the hand-written locking read; the other asset
     * reads back with none on every path.
     */
    val quality = ImageQuality(sharpness = 123.5, luminance = 0.45, clippedHighlights = 0.01, crushedShadows = 0.002, score = 87)
    val hash = PerceptualHash(Long.MinValue + 42)
    val measured = testApp.service.asset.markAsCompleted(
      testApp.service.asset.add(
        testContext.makeAsset(isTriaged = true).copy(imageQuality = Some(quality), perceptualHash = Some(hash))))
    val unmeasured = testApp.service.asset.markAsCompleted(testApp.service.asset.add(testContext.makeAsset()))
    val sequenceId = BaseDao.genId
    testApp.txManager.withTransaction(testApp.DAO.asset.setSequence(Set(measured.persistedId), sequenceId)) shouldBe 1

    def check(assets: Map[String, Asset]): Unit = {
      assets(measured.persistedId).imageQuality shouldBe Some(quality)
      assets(measured.persistedId).perceptualHash shouldBe Some(hash)
      assets(measured.persistedId).sequenceId shouldBe Some(sequenceId)
      assets(unmeasured.persistedId).imageQuality shouldBe None
      assets(unmeasured.persistedId).perceptualHash shouldBe None
      assets(unmeasured.persistedId).sequenceId shouldBe None
    }

    val ids = Set(measured.persistedId, unmeasured.persistedId)
    check(ids.map(id => id -> (testApp.service.asset.getById(id): Asset)).toMap)
    check(testApp.service.asset.query(new Query()).records.map(asset => asset.persistedId -> asset).toMap)
    check(
      testApp.txManager.withTransaction(testApp.DAO.asset.getAssetsToMove(ids)).map(asset => asset.persistedId -> asset).toMap)
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

  test("The assets in a set of folders are found by ID, recycled ones included, in the context repository alone") {

    /**
     * Setup:
     *
     * A folder with a child folder, an asset in each, the child's recycled; an asset in another folder and one in the root. A
     * second repository has a folder of the same name holding an asset.
     *
     * Assertions:
     *
     * The IDs in the folder, its child and the second repository's folder are those of the two assets in the folder and its
     * child.
     *
     * Edge cases:
     *
     * The recycled asset is among them, the second repository's is not, and no folders find no assets.
     */
    val repository: Repository = testContext.repository
    val trips = testApp.service.folder.add("Trips")
    val rome = testApp.service.folder.add("Rome", Some(trips.persistedId))
    val other = testApp.service.folder.add("Other")
    val inTrips = testContext.insertAsset(testContext.makeAsset(folder = Some(trips)))
    val inRome = testContext.insertAsset(testContext.makeAsset(folder = Some(rome)))
    testContext.insertAsset(testContext.makeAsset(folder = Some(other)))
    testContext.insertAsset()
    testApp.service.library.recycleAssets(Set(inRome.persistedId))

    val secondRepository = testContext.persistRepository()
    switchContextRepo(secondRepository)
    val foreignTrips = testApp.service.folder.add("Trips")
    testContext.insertAsset(testContext.makeAsset(repository = Some(secondRepository), folder = Some(foreignTrips)))
    switchContextRepo(repository)

    testApp.service.asset.getIdsInFolders(Set(trips.persistedId, rome.persistedId, foreignTrips.persistedId)) shouldBe
      Set(inTrips.persistedId, inRome.persistedId)
    testApp.service.asset.getIdsInFolders(Set.empty) shouldBe Set.empty
  }
}
