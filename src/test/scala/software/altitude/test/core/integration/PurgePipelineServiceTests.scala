package software.altitude.test.core.integration
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.core.NotFoundException
import software.altitude.core.models.Asset
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.pipeline.PipelineTypes.PipelineContext
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.pipeline.sinks.VoidAssetSink
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

@DoNotDiscover class PurgePipelineServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore {

  test("Purging assets should remove asset data from DB and file store") {
    val totalAssets = 5
    val assets = List.fill(totalAssets)(testContext.persistAsset())

    // recycle some assets
    val recycleCount = 3
    val recycledAssets = assets.take(recycleCount) map { asset =>
      testApp.service.library.recycleAsset(asset.persistedId)
      asset
    }

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => recycledAssets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]]  = testApp.service.purgePipeline.run(source, VoidAssetSink())
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

    // TODO: check decoy person is still there
  }

  test("Purging assets should remove face data from DB and file store", Focused) {
    val assetsPerPerson = 3
    val totalPeople = 3
    val people = List.fill(totalPeople)(testApp.service.person.addPerson(Person()))
    // 1 face for each person in an asset, so total will be totalAssets * totalPeople
    testContext.addTestFacesAndAssets(people, assetCount = assetsPerPerson)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records.map(Asset.fromJson)

    // add a decoy person/face to make sure we don't delete the wrong ones
    val extraPerson = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(extraPerson, assetCount = assetsPerPerson)

    // Create a map of personId to face object, for easier lookup
    // These are only the people in the deleted assets
    val personIdToFaceMap: Map[String, List[Face]] = people.map { person =>
      person.persistedId -> testApp.service.person.getPersonFaces(person.persistedId)
    }.toMap

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]]  = testApp.service.purgePipeline.run(source, VoidAssetSink())
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
    val assetsPerPerson = 3
    val totalPeople = 3
    val people = List.fill(totalPeople)(testApp.service.person.addPerson(Person()))
    testContext.addTestFacesAndAssets(people, assetCount = assetsPerPerson)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records.map(Asset.fromJson)

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture1: Future[Seq[TAssetWithContext]]  = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture1, Duration.Inf)

    val pipelineResFuture2: Future[Seq[TAssetWithContext]]  = testApp.service.purgePipeline.run(source, VoidAssetSink())
    Await.result(pipelineResFuture2, Duration.Inf)

    // Not testing any conditions - just that this code doesn't throw an exception
  }

  test("Purging an asset does not delete a face asset that is marked as COVER") {
    val assetsPerPerson = 3
    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, assetCount = assetsPerPerson)

    // this face binary data should NOT be purged
    val coverFace = testApp.service.person.getPersonFaces(person.persistedId).head
    testApp.service.person.setFaceAsCover(person, coverFace)

    val assets: List[Asset] = testApp.service.asset.queryAll(new Query()).records.map(Asset.fromJson)

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => assets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetWithContext]]  = testApp.service.purgePipeline.run(source, VoidAssetSink())
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

}
