package software.altitude.test.core.integration
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.core.NotFoundException
import software.altitude.core.models.Asset
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.pipeline.PipelineTypes.PipelineContext
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

// FIXME: make generic with the default VoidAssetSink
object VoidAssetSink {
  def apply(): Sink[TAssetWithContext, Future[Seq[TAssetWithContext]]] =
    Sink.fold(Seq.empty[TAssetWithContext])((acc, _) => acc)
}


@DoNotDiscover class PurgePipelineServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore {

  test("Purging assets should remove asset data from DB and file store", Focused) {
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

      // check that all faces for the asset are gone in DB and file store
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
      }
    }
  }
}
