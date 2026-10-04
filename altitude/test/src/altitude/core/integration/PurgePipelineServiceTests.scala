package altitude.core.integration

import altitude.test.IntegrationTestUtil
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.util.Query

@DoNotDiscover class PurgePipelineServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

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

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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
     * Every face of the three people is gone from the database, and all four of its images from the file store, except each
     * person's cover face, whose images a purge keeps. The decoy's faces, in assets outside the purge, keep their records and
     * images.
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

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture, Duration.Inf)

    for (person <- people) {
      val personFaces = personIdToFaceMap(person.persistedId)

      // cover face was assigned to person record AFTER it had been persisted, so get it from the DB
      val personCoverFaceId = (testApp.service.person.getById(person.persistedId): Person).coverFaceId.getOrElse("")

      // Check that all faces for the asset are gone in DB and file store.
      for (face <- personFaces if face.persistedId != personCoverFaceId) {
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

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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

  test("Purging an asset does not delete a face asset that is marked as COVER") {

    /**
     * Setup:
     *
     * One person with a face in each of three assets, the first face set as the person's cover; all three assets are purged.
     *
     * Assertions:
     *
     * The cover face's record goes with its asset, but all four of its images stay in the file store.
     */
    val assetsPerPerson = 3
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = assetsPerPerson)

    // this face binary data should NOT be purged
    val coverFace = testApp.service.person.getPersonFaces(person.persistedId).head
    testApp.service.person.setFaceAsCover(person, coverFace)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture, Duration.Inf)

    // Face record will be deleted according to cascade rules, but the binary data should still be there
    intercept[NotFoundException] {
      testApp.service.person.getFaceById(coverFace.persistedId)
    }

    testApp.service.fileStore.getDisplayFaceById(coverFace.persistedId)
    testApp.service.fileStore.getAlignedGreyscaleFaceById(coverFace.persistedId)
    testApp.service.fileStore.getAlignedFaceById(coverFace.persistedId)
    testApp.service.fileStore.getDetectedFaceById(coverFace.persistedId)
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
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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
}
