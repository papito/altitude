package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.a
import org.scalatest.matchers.must.Matchers.have
import org.scalatest.matchers.should.Matchers.{ be, convertNumericToPlusOrMinusWrapper, should, shouldBe }
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.RequestContext
import altitude.core.UnsupportedMediaTypeException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.pipeline.sinks.VoidAssetSink

@DoNotDiscover class ImportPipelineServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Void pipeline sink should produce no results") {

    /**
     * Setup:
     *
     * Five assets over staged random images, streamed through the import pipeline into the void sink.
     *
     * Assertions:
     *
     * The pipeline runs to completion and the sink hands back no results.
     */
    val batchSize = 5
    val dataAssets = (1 to batchSize).map(_ => testContext.makeAssetWithData())

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => dataAssets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] = testApp.service.importPipeline.run(source, VoidAssetSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size 0
  }

  test("Pipeline should import multiple assets") {

    /**
     * Setup:
     *
     * Ten assets over staged random images, streamed through the import pipeline into a sink that collects every result.
     *
     * Assertions:
     *
     * Every asset comes out of the pipeline imported, and each is marked as pipeline-processed in the repository.
     */
    val batchSize = 10
    val dataAssets = (1 to batchSize).map(_ => testContext.makeAssetWithData())

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => dataAssets.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size batchSize

    pipelineRes.foreach {
      case (Left(asset), _) =>
        val persistedAsset: Asset = testApp.service.asset.getById(asset.persistedId)
        persistedAsset.isPipelineProcessed shouldBe true

      case (Right(invalid), _) => fail(s"Expected every element to be imported: ${invalid.cause}")
    }
  }

  test("Assets queued at once, as concurrent uploads queue them, are all imported") {

    /**
     * Setup:
     *
     * Six assets over staged random images offered to the long-running import queue all at once, every offer made before any is
     * awaited.
     *
     * Assertions:
     *
     * All six end up pipeline-processed in the repository, polled for up to a minute.
     */
    val batchSize = 6
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val repositoryId = RequestContext.getRepository.persistedId

    // Every offer is made before any is awaited
    val offers =
      (1 to batchSize).map(_ => testApp.service.importPipeline.addToQueue((testContext.makeAssetWithData(), pipelineContext)))
    Await.result(Future.sequence(offers)(implicitly, scala.concurrent.ExecutionContext.global), 30.seconds)

    def imported: Int = testApp.txManager.asReadOnly {
      query("SELECT count(*) AS n FROM asset WHERE repository_id = ? AND is_pipeline_processed = ?", repositoryId, true)
        .head("n")
        .toString
        .toInt
    }

    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(200, Millis))) {
      imported shouldBe batchSize
    }
  }

  test("Pipeline should complete on duplicate asset errors") {

    /**
     * Setup:
     *
     * Ten assets over staged random images, the first one sent twice in a row, for eleven elements.
     *
     * Assertions:
     *
     * The pipeline completes with a result for every element: ten imports, each pipeline-processed in the repository, and the
     * repeated element rejected as a duplicate.
     */
    val batchSize = 10
    val dataAssets = (1 to batchSize).map(_ => testContext.makeAssetWithData())

    // now it's 11, but the second one is a duplicate
    val dataAssetsWithDuplicate = dataAssets.head +: dataAssets

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source = Source.fromIterator(() => dataAssetsWithDuplicate.iterator).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size batchSize + 1

    val validAssetsCount = pipelineRes.count {
      case (Left(asset), _) => testApp.service.asset.getById(asset.persistedId).isPipelineProcessed
      case (Right(_), _) => false
    }
    validAssetsCount shouldBe batchSize

    val invalidAssetsCount = pipelineRes.count {
      case (Right(_), _) => true
      case _ => false
    }
    invalidAssetsCount shouldBe 1

    // the second element is a duplicate
    pipelineRes(1) match {
      case (Right(invalid), _) => invalid.cause.get shouldBe a[DuplicateException]
      case _ => fail("Expected the second element to be rejected as a duplicate")
    }
  }

  test("Pipeline should complete on unsupported media type errors") {

    /**
     * Setup:
     *
     * One asset over a staged random image, labelled with a made-up media type ("bad/type").
     *
     * Assertions:
     *
     * The pipeline completes with the asset rejected as an unsupported media type, and its staged file deleted.
     */
    val badMediaType = AssetType("bad", "type", "mime")
    val assetWithBadMediaType = testContext.makeAsset().copy(assetType = badMediaType)
    val assetWithData = testContext.makeAssetWithData(asset = Some(assetWithBadMediaType))

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source.single(assetWithData, pipelineContext)

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size 1

    pipelineRes.head match {
      case (Right(invalid), _) => invalid.cause.get shouldBe a[UnsupportedMediaTypeException]
      case _ => fail("Expected the first element to be rejected as an unsupported media type")
    }

    // A dropped asset leaves no staged file behind
    Files.exists(assetWithData.path) shouldBe false
  }

  test("Pipeline should complete on a video no frame of which decodes, and go on importing") {

    /**
     * Setup:
     *
     * A clip whose media data is zeroed (TestVideos.undecodable), so it probes as a two-second video but no frame of it decodes,
     * followed by an asset over a staged random image.
     *
     * Assertions:
     *
     * The pipeline completes with the clip dropped and its staged file deleted, and the photo behind it is imported.
     */
    val clip =
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.undecodable.toFile))
    val photo = testContext.makeAssetWithData()

    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source(List(clip, photo)).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size 2

    // The Preview has no frame to take, which drops the clip rather than failing the pipeline
    pipelineRes.head match {
      case (Right(invalid), _) => invalid.cause.get shouldBe a[RuntimeException]
      case _ => fail("Expected the clip to be dropped")
    }
    Files.exists(clip.path) shouldBe false

    pipelineRes(1) match {
      case (Left(asset), _) => testApp.service.asset.getById(asset.persistedId).isPipelineProcessed shouldBe true
      case _ => fail("Expected the photo to be imported")
    }
  }

  test("Pipeline stores the coordinates a photo carries") {

    /**
     * Setup:
     *
     * A JPEG geotagged at 33.857 N, 151.2152 E (images/exif/gps-north-east.jpg), imported through the pipeline.
     *
     * Assertions:
     *
     * The asset stores the coordinates as decimal degrees, and its extracted metadata keeps the raw GPS directory, ref tags
     * included.
     */
    val dataAsset =
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.getImportAsset("images/exif/gps-north-east.jpg"))
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source.single(dataAsset, pipelineContext)

    val pipelineRes = Await.result(testApp.service.importPipeline.run(source, AssetSeqOutputSink()), Duration.Inf)
    pipelineRes should have size 1

    val persisted = pipelineRes.head match {
      case (Left(asset), _) => testApp.service.asset.getById(asset.persistedId)
      case (Right(invalid), _) => fail(s"Import failed: ${invalid.cause}")
    }
    persisted.latitude.get should be(33.857 +- 1e-4)
    persisted.longitude.get should be(151.2152 +- 1e-4)
    persisted.extractedMetadata.getFieldValues("GPS").get("GPS Latitude Ref") shouldBe Some("N")
  }
}
