package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.util.Query

@DoNotDiscover class PurgePipelineServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  /** Has the database refuse to delete the asset's row, as a failing statement would, until the returned cleanup runs */
  private def refuseDeleting(assetId: String): () => Unit = refuse("DELETE", "asset", assetId)

  /** Has the database refuse to change the Person's cover, as a failing statement would, until the returned cleanup runs */
  private def refuseMovingCover(personId: String): () => Unit = refuse("UPDATE OF cover_face_id", "person", personId)

  test("Purging assets should remove asset data from DB and file store") {

    /**
     * Setup:
     *
     * Five assets over random images, three of them recycled one by one; the three recycled ones run through the purge pipeline.
     *
     * Assertions:
     *
     * Each purged asset is gone from the database, and its preview and file are gone from the file store.
     */
    val totalAssets = 5
    val assets = List.fill(totalAssets)(testContext.persistAsset())

    // recycle some assets
    val recycleCount = 3
    val recycledAssets = assets.take(recycleCount).map {
      asset =>
        testApp.service.library.recycleAssets(Set(asset.persistedId))
        asset
    }

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val source = Source.fromIterator(() => recycledAssets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture, Duration.Inf)

    // the purged assets should be gone
    for (asset <- recycledAssets) {
      intercept[NotFoundException] {
        testApp.service.asset.getById(asset.persistedId)
      }
      intercept[NotFoundException] {
        testApp.service.fileStore.getPreviewById(asset.persistedId)
      }
      intercept[NotFoundException] {
        testApp.service.fileStore.getAssetById(asset.persistedId)
      }
    }
  }

  test("Purging assets should remove face data from DB and file store") {

    /**
     * Setup:
     *
     * Three people with a face each in three shared assets (nine faces), plus a decoy person with faces in three more assets.
     * Only the first three assets run through the purge pipeline.
     *
     * Assertions:
     *
     * Every face of the three people is gone from the database, and all four of its images from the file store, each person's
     * cover face included. The decoy's faces, in assets outside the purge, keep their records and images.
     */
    val assetsPerPerson = 3
    val totalPeople = 3
    val people = List.fill(totalPeople)(testApp.service.person.addPerson(Person()))
    // 1 face for each person in an asset, so total will be totalAssets * totalPeople
    testContext.addTestFacesAndAssets(people, assetCount = assetsPerPerson)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records

    // add a decoy person/face to make sure we don't delete the wrong ones
    val extraPerson = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(extraPerson, assetCount = assetsPerPerson)

    // Create a map of personId to face object, for easier lookup
    // These are only the people in the deleted assets
    val personIdToFaceMap: Map[String, List[Face]] =
      people.map(person => person.persistedId -> testApp.service.person.getPersonFaces(person.persistedId)).toMap

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture, Duration.Inf)

    for (person <- people) {
      val personFaces = personIdToFaceMap(person.persistedId)

      // Check that all faces for the asset are gone in DB and file store.
      for (face <- personFaces) {
        intercept[NotFoundException] {
          testApp.service.person.getFaceById(face.persistedId)
        }
        intercept[NotFoundException] {
          testApp.service.fileStore.getDisplayFaceById(face.persistedId)
        }
        intercept[NotFoundException] {
          testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
        }
        intercept[NotFoundException] {
          testApp.service.fileStore.getAlignedFaceById(face.persistedId)
        }
        intercept[NotFoundException] {
          testApp.service.fileStore.getDetectedFaceById(face.persistedId)
        }
      }
    }

    val decoyFaces = testApp.service.person.getPersonFaces(extraPerson.persistedId)
    decoyFaces should have size assetsPerPerson
    for (face <- decoyFaces) {
      testApp.service.fileStore.getDisplayFaceById(face.persistedId)
      testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
      testApp.service.fileStore.getAlignedFaceById(face.persistedId)
      testApp.service.fileStore.getDetectedFaceById(face.persistedId)
    }
  }

  test("Purging assets twice should be a NO-OP") {

    /**
     * Setup:
     *
     * Three people with a face each in three assets, all three recycled, and one more asset outside the purge. The recycled
     * assets run through the purge pipeline twice.
     *
     * Assertions:
     *
     * The second purge, of assets already purged, completes and changes nothing: the purged assets and their files stay gone, and
     * the asset outside the purge keeps its record and file.
     */
    val assetsPerPerson = 3
    val totalPeople = 3
    val people = List.fill(totalPeople)(testApp.service.person.addPerson(Person()))
    testContext.addTestFacesAndAssets(people, assetCount = assetsPerPerson)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records
    testApp.service.library.recycleAssets(assets.map(_.persistedId).toSet)

    val survivor = testContext.persistAsset()

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)
    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)

    for (asset <- assets) {
      intercept[NotFoundException] {
        testApp.service.asset.getById(asset.persistedId)
      }
      intercept[NotFoundException] {
        testApp.service.fileStore.getAssetById(asset.persistedId)
      }
    }
    testApp.service.asset.getById(survivor.persistedId)
    testApp.service.fileStore.getAssetById(survivor.persistedId)
  }

  /** Recycles the assets and marks them for purging, as a purge request leaves them before the purge queue takes them */
  private def markForPurging(assets: Asset*): Unit = {
    testApp.service.library.recycleAssets(assets.map(_.persistedId).toSet)
    assets.foreach(asset => testApp.service.asset.updateById(asset.persistedId, Map(FieldConst.Asset.IS_PURGED -> true)))
  }

  /** Runs the assets through the purge pipeline, and waits for it */
  private def purge(assets: Asset*): Unit = {
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val source = Source(assets.toList).map((_, pipelineContext))
    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)
  }

  /** Whether any of the four images of the face is in the file store */
  private def hasFaceFiles(face: Face): Boolean =
    List(
      () => testApp.service.fileStore.getDisplayFaceById(face.persistedId),
      () => testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId),
      () => testApp.service.fileStore.getAlignedFaceById(face.persistedId),
      () => testApp.service.fileStore.getDetectedFaceById(face.persistedId)
    ).exists {
      read =>
        try { read(); true }
        catch { case _: NotFoundException => false }
    }

  test("Purging an asset that holds a Person's cover gives them their best Face left, and the old cover's files go") {

    /**
     * Setup:
     *
     * A Person with Faces on three assets: the first, their cover, at a detection score of 0.9, the second at 0.5 and the third
     * at 0.7, its asset marked for purging. A second Person whose only Face, their cover, is on the first asset. The first asset,
     * marked for purging, goes through the purge pipeline.
     *
     * Assertions:
     *
     * The first Person's cover is their Face on the second asset, the best of those on an asset not marked for purging, and the
     * second Person has no cover. The old covers' rows are gone, and so are all four images of each.
     *
     * Edge cases:
     *
     * The third asset's Face scores higher than the new cover, but its asset is marked for purging.
     */
    val added = testApp.service.person.addPerson(Person())
    val assets = List.fill(3)(testContext.insertAsset())
    val faces = assets.zip(List(0.9, 0.5, 0.7)).zipWithIndex.map {
      case ((asset, score), n) =>
        testContext.addTestFace(
          testApp.service.person.getById(added.persistedId),
          asset,
          TestContext.unitVector(n),
          detectionScore = score,
          withFiles = true)
    }
    val alone = testApp.service.person.addPerson(Person())
    val aloneCover = testContext.addTestFace(alone, assets.head, TestContext.unitVector(9), x1 = 50, withFiles = true)
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(faces.head.persistedId)
    testApp.service.person.getById(alone.persistedId).coverFaceId shouldBe Some(aloneCover.persistedId)

    markForPurging(assets.head, assets(2))
    purge(assets.head)

    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(faces(1).persistedId)
    testApp.service.person.getById(alone.persistedId).coverFaceId shouldBe None
    List(faces.head, aloneCover).foreach {
      face =>
        intercept[NotFoundException](testApp.service.person.getFaceById(face.persistedId))
        hasFaceFiles(face) shouldBe false
    }
  }

  test("A purge of several assets that hold a Person's best Faces repicks their cover onto none of them") {

    /**
     * Setup:
     *
     * A Person with Faces on three assets at detection scores of 0.9 (their cover), 0.8 and 0.3. The first two assets are marked
     * for purging together, and the first goes through the purge pipeline, then the second.
     *
     * Assertions:
     *
     * Once the first asset is purged, while the second still waits for the queue, the Person's cover is their Face on the third
     * asset, not the better one on the second; it stays there once the second is purged.
     */
    val added = testApp.service.person.addPerson(Person())
    val assets = List.fill(3)(testContext.insertAsset())
    val faces = assets.zip(List(0.9, 0.8, 0.3)).zipWithIndex.map {
      case ((asset, score), n) =>
        testContext.addTestFace(
          testApp.service.person.getById(added.persistedId),
          asset,
          TestContext.unitVector(n),
          detectionScore = score)
    }

    markForPurging(assets.head, assets(1))
    purge(assets.head)
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(faces(2).persistedId)

    purge(assets(1))
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(faces(2).persistedId)
  }

  test("Purging assets should only delete face data for the purged assets") {

    /**
     * Setup:
     *
     * Three images of one face (people/meme-ben.jpg, meme-ben2.png, meme-ben3.png) imported. The first is recycled and run
     * straight through the purge pipeline.
     *
     * Assertions:
     *
     * The two assets that were not purged still have their faces, and every image of those faces is still in the file store.
     */
    val assets = List("people/meme-ben.jpg", "people/meme-ben2.png", "people/meme-ben3.png").map {
      path => testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(path))
    }

    val recycledAsset = assets.head
    testApp.service.library.recycleAssets(Set(recycledAsset.persistedId))

    // purgeRecycleBin() only queues the purge, so the asset runs straight through the pipeline, which can be awaited
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val purgeSource = Source.single((recycledAsset, pipelineContext))
    Await.result(testApp.service.purgePipeline.run(purgeSource, VoidAssetSink()), Duration.Inf)

    for (asset <- assets.tail) {
      val faces = testApp.service.person.getAssetFaces(asset.persistedId)
      faces should not be empty

      for (face <- faces) {
        testApp.service.fileStore.getDisplayFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedFaceById(face.persistedId)
        testApp.service.fileStore.getDetectedFaceById(face.persistedId)
      }
    }
  }

  test("Purging the recycle bin should not affect other assets") {

    /**
     * Setup:
     *
     * Three portraits (people/damon.jpg, affleck.jpg, bullock.jpg) imported. The first two are recycled and the recycle bin is
     * purged, which only queues them, so the test polls for up to a minute until the purge has deleted their records.
     *
     * Assertions:
     *
     * The asset that was not recycled is still in the database, with its preview, its file and its faces, every one of their
     * images included.
     */
    val assets = List("people/damon.jpg", "people/affleck.jpg", "people/bullock.jpg").map {
      path => testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(path))
    }

    val (recycledAssets, nonRecycledAssets) = assets.splitAt(2)
    testApp.service.library.recycleAssets(recycledAssets.map(_.persistedId).toSet)

    testApp.service.library.purgeRecycleBin()

    // Deleting the records is the purge pipeline's last stage, so the files are gone by then too
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(200, Millis))) {
      for (asset <- recycledAssets) {
        intercept[NotFoundException] {
          testApp.service.asset.getById(asset.persistedId)
        }
      }
    }

    for (asset <- nonRecycledAssets) {
      testApp.service.asset.getById(asset.persistedId)
      testApp.service.fileStore.getPreviewById(asset.persistedId)
      testApp.service.fileStore.getAssetById(asset.persistedId)

      val faces = testApp.service.person.getAssetFaces(asset.persistedId)
      faces should not be empty

      for (face <- faces) {
        testApp.service.fileStore.getDisplayFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedFaceById(face.persistedId)
        testApp.service.fileStore.getDetectedFaceById(face.persistedId)
      }
    }
  }

  test("A recycle bin larger than the purge queue buffers is emptied completely", Slow) {

    /**
     * Setup:
     *
     * Twice as many recycled assets as the purge queue buffers (`parallelism` times two), persisted and completed without files,
     * and the recycle bin emptied.
     *
     * Assertions:
     *
     * The purge queue deletes every one of them.
     */
    val binSize = testApp.parallelism * 4
    // Each made asset has a random checksum of its own, as it must: only one live asset of a content is allowed
    val ids = (1 to binSize).map {
      _ =>
        val asset = testApp.service.library.persistAndIndex(testContext.makeAsset())
        testApp.service.library.completeImport(asset).persistedId
    }.toSet
    testApp.service.library.recycleAssets(ids)

    testApp.service.library.purgeRecycleBin()

    eventually {
      testApp.service.asset.queryAll(new Query()).total shouldBe 0
    }
  }

  test("An asset whose row cannot be deleted does not stop the purge queue") {

    /**
     * Setup:
     *
     * Two recycled assets, the database refusing to delete the first one's row. The first is purged and, once the queue has
     * deleted its files, so it is ahead of the second, the second is purged.
     *
     * Assertions:
     *
     * The queue goes on to delete the second asset's row and file. The first asset's row stays, still marked for purging, for the
     * startup job to queue again.
     */
    val refused = testContext.persistAsset()
    val next = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(refused.persistedId, next.persistedId))

    val allowDeleting = refuseDeleting(refused.persistedId)
    try {
      testApp.service.library.purgeSelectedAssets(Set(refused.persistedId))
      eventually {
        intercept[NotFoundException](testApp.service.fileStore.getAssetById(refused.persistedId))
      }

      testApp.service.library.purgeSelectedAssets(Set(next.persistedId))
      eventually {
        intercept[NotFoundException](testApp.service.asset.getById(next.persistedId))
      }
    } finally allowDeleting()

    intercept[NotFoundException](testApp.service.fileStore.getAssetById(next.persistedId))
    val left = testApp.service.asset.queryRecycled(new Query().add(FieldConst.Asset.IS_PURGED -> true)).records
    left.map(_.persistedId) shouldBe List(refused.persistedId)
  }

  test("An asset whose covers cannot be moved off its Faces stays whole and purge-pending, and is purged once queued again") {

    /**
     * Setup:
     *
     * A Person with Faces on two assets: the first, their cover, at a detection score of 0.9, with its files, on an asset that is
     * recycled and marked for purging; the second at 0.5. The database refuses to change the Person's cover while the first asset
     * goes through the purge pipeline; then the refusal is lifted and the startup job queues the purge-pending assets again.
     *
     * Assertions:
     *
     * While the cover cannot move, the purge leaves the asset whole: its row, still marked for purging, its file, and the cover
     * Face's row and images, with the Person's cover still on it. Once queued again, the Person's cover is their Face on the
     * second asset, and the asset's row and file and the old cover's row and images are gone.
     */
    val added = testApp.service.person.addPerson(Person())
    val purged = testContext.persistAsset()
    val cover = testContext.addTestFace(added, purged, TestContext.unitVector(1), detectionScore = 0.9, withFiles = true)
    val next = testContext.addTestFace(
      testApp.service.person.getById(added.persistedId),
      testContext.insertAsset(),
      TestContext.unitVector(2),
      detectionScore = 0.5)
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(cover.persistedId)
    markForPurging(purged)

    val allowMovingCover = refuseMovingCover(added.persistedId)
    try purge(purged)
    finally allowMovingCover()

    val pending = new Query().add(FieldConst.ID -> purged.persistedId).add(FieldConst.Asset.IS_PURGED -> true)
    testApp.service.asset.queryAll(pending).records.map(_.persistedId) shouldBe List(purged.persistedId)
    testApp.service.fileStore.getAssetById(purged.persistedId)
    testApp.service.person.getFaceById(cover.persistedId)
    hasFaceFiles(cover) shouldBe true
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(cover.persistedId)

    testApp.service.library.requeuePurgePending()

    eventually {
      intercept[NotFoundException](testApp.service.asset.getById(purged.persistedId))
    }
    intercept[NotFoundException](testApp.service.fileStore.getAssetById(purged.persistedId))
    intercept[NotFoundException](testApp.service.person.getFaceById(cover.persistedId))
    hasFaceFiles(cover) shouldBe false
    testApp.service.person.getById(added.persistedId).coverFaceId shouldBe Some(next.persistedId)
  }

  test("An asset left marked for purging is purged once the startup job queues it again") {

    /**
     * Setup:
     *
     * A recycled asset marked for purging directly, as a purge leaves it when the app stops before the queue deletes it; then the
     * startup job that queues such assets again.
     *
     * Assertions:
     *
     * The asset's row, file and preview are deleted.
     *
     * Edge cases:
     *
     * The job queues the asset with no account, since no request did and a Library has no owner, and the purge needs none.
     */
    val asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.asset.updateById(asset.persistedId, Map(FieldConst.Asset.IS_PURGED -> true))

    testApp.service.library.requeuePurgePending()

    eventually {
      intercept[NotFoundException](testApp.service.asset.getById(asset.persistedId))
    }
    intercept[NotFoundException](testApp.service.fileStore.getAssetById(asset.persistedId))
    intercept[NotFoundException](testApp.service.fileStore.getPreviewById(asset.persistedId))
  }
}
