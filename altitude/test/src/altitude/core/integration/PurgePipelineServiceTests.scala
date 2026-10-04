package altitude.core.integration

import altitude.test.IntegrationTestUtil
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
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
     * person's cover face, whose images a purge keeps.
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
  }

  test("Purging assets twice should be a NO-OP") {

    /**
     * Setup:
     *
     * Three people with a face each in three assets; the same assets run through the purge pipeline twice, without being
     * recycled.
     *
     * Assertions:
     *
     * The second purge, of assets already purged, completes without throwing; nothing else is checked.
     */
    val assetsPerPerson = 3
    val totalPeople = 3
    val people = List.fill(totalPeople)(testApp.service.person.addPerson(Person()))
    testContext.addTestFacesAndAssets(people, assetCount = assetsPerPerson)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture1: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture1, Duration.Inf)

    val pipelineResFuture2: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture2, Duration.Inf)

    // Not testing any conditions - just that this code doesn't throw an exception
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
     * Three images of one face (people/meme-ben.jpg, meme-ben2.png, meme-ben3.png) imported, then fed through the import pipeline
     * a second time. One of the assets is recycled and run straight through the purge pipeline, since purgeRecycleBin only queues
     * the purge and cannot be awaited.
     *
     * Assertions:
     *
     * Every face image of the two assets that were not purged is still in the file store.
     */
    val importAssetPaths = List(
      "people/meme-ben.jpg",
      "people/meme-ben2.png",
      "people/meme-ben3.png"
    )

    val assetsWithData = importAssetPaths.map {
      path =>
        val importAsset = IntegrationTestUtil.getImportAsset(path)
        val asset = testApp.service.library.addImportAsset(importAsset)
        AssetWithData(asset, testApp.service.staging.stageCopy(importAsset.path))
    }

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)

    val importSource = Source.fromIterator(() => assetsWithData.iterator).map((_, pipelineContext))
    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(importSource, AssetSeqOutputSink())
    Await.result(pipelineResFuture, Duration.Inf)

    // Recycle one
    val assets: List[Asset] = testApp.service.asset.queryAll(new Query(rpp = 1)).records
    val recycledAsset = assets.head
    testApp.service.library.recycleAssets(Set(recycledAsset.persistedId))

    // Purge
    // !!! NOTE: We cannot use purgeRecycleBin() directly as it creates a race condition -
    // the assets are just added to the queue to be processed when the system is good and ready.
    // This is a workaround to force the assets to be purged immediately so we can test the result.
    val purgeSource = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))
    val purgePipelineResFuture: Future[Seq[TAssetWithContext]] = testApp.service.purgePipeline.run(purgeSource, VoidAssetSink())
    Await.result(purgePipelineResFuture, Duration.Inf)

    // make sure the data for non-recycled assets is still there
    val nonRecycledAssets = assetsWithData.filterNot(_.asset.persistedId == recycledAsset.persistedId).map(_.asset)

    for (asset <- nonRecycledAssets) {
      val faces = testApp.service.person.getAssetFaces(asset.persistedId)

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
     * Three portraits (people/damon.jpg, affleck.jpg, bullock.jpg) imported, then fed through the import pipeline a second time.
     * Two of them are recycled and the recycle bin is purged.
     *
     * Assertions:
     *
     * The asset that was not recycled is still in the database, with its preview, its file and every one of its face images.
     */
    val importAssetPaths = List(
      "people/damon.jpg",
      "people/affleck.jpg",
      "people/bullock.jpg"
    )

    val assetsWithData = importAssetPaths.map {
      path =>
        val importAsset = IntegrationTestUtil.getImportAsset(path)
        val asset = testApp.service.library.addImportAsset(importAsset)
        AssetWithData(asset, testApp.service.staging.stageCopy(importAsset.path))
    }

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assetsWithData.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    Await.result(pipelineResFuture, Duration.Inf)

    // recycle some assets
    val recycleCount = 2
    val recycledAssets = assetsWithData.take(recycleCount).map {
      assetWithData =>
        testApp.service.library.recycleAssets(Set(assetWithData.asset.persistedId))
        assetWithData.asset
    }

    testApp.service.library.purgeRecycleBin()

    val nonRecycledAssets = assetsWithData.filterNot(assetWithData => recycledAssets.contains(assetWithData.asset)).map(_.asset)

    for (asset <- nonRecycledAssets) {
      testApp.service.asset.getById(asset.persistedId)
      testApp.service.fileStore.getPreviewById(asset.persistedId)
      testApp.service.fileStore.getAssetById(asset.persistedId)

      val faces = testApp.service.person.getAssetFaces(asset.persistedId)

      for (face <- faces) {
        testApp.service.fileStore.getDisplayFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
        testApp.service.fileStore.getAlignedFaceById(face.persistedId)
        testApp.service.fileStore.getDetectedFaceById(face.persistedId)
      }
    }
  }
}
