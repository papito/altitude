package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import org.apache.commons.io.FileUtils
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.Materializer.matFromSystem
import org.apache.pekko.stream.Supervision
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
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
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.DurationLong
import scala.jdk.CollectionConverters.*
import scala.util.Try

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.ImageException
import altitude.core.ImportDestinationException
import altitude.core.NotFoundException
import altitude.core.QueueRefusedException
import altitude.core.RequestContext
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Import
import altitude.core.models.ImportFile
import altitude.core.models.ImportOrigin
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Person
import altitude.core.models.Repository
import altitude.core.models.Stats
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils
import altitude.core.pipeline.QueuedPipeline
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.service.ImportPipelineService
import altitude.core.util.Query

@DoNotDiscover class ImportPipelineServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(200, Millis)))(f)

  /** How many assets of the repository have finished importing */
  private def importedCount(repositoryId: String = testContext.repository.persistedId): Int = testApp.txManager.asReadOnly {
    query("SELECT count(*) AS n FROM asset WHERE repository_id = ? AND is_pipeline_processed = ?", repositoryId, true)
      .head("n")
      .toString
      .toInt
  }

  /** Runs the assets through the import pipeline, each in its own context, and hands back every result */
  private def runPipeline(assets: (AssetWithData, PipelineContext)*): Seq[TAssetOrInvalidWithContext] =
    Await.result(testApp.service.importPipeline.run(Source(assets.toList), AssetSeqOutputSink()), Duration.Inf)

  /** Bytes no image reader takes, staged under a JPEG asset, as an upload with a misleading type would arrive */
  private def notAnImage(): AssetWithData =
    AssetWithData(
      testContext.makeAsset().copy(assetType = AssetType("image", "jpeg", "image/jpeg")),
      testApp.service.staging.stage("not an image".getBytes))

  /**
   * A queue of the test's own over a flow that fails its stream on the element "bad" (its stage stops on a failure, rather than
   * resuming as the queue's stages do) and records every other element it passes
   */
  private def failingQueue(restartOnFailure: Boolean, passed: ConcurrentLinkedQueue[String]): QueuedPipeline[String] =
    val flow = Flow[String]
      .map {
        element =>
          if element == "bad" then throw RuntimeException("Failed by the test")
          passed.add(element)
          element
      }
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.stoppingDecider))

    QueuedPipeline[String](
      "test",
      flow,
      describe = identity,
      bufferSize = 2,
      maxConcurrentOffers = 2,
      shutdownTimeout = () => 30.seconds,
      restartOnFailure = restartOnFailure)(using testApp.actorSystem)

  /** The pipeline's result for the asset of a file name, imported or dropped */
  private def resultFor(results: Seq[TAssetOrInvalidWithContext], fileName: String): TAssetOrInvalidWithContext =
    results.find(_._1.fold(_.fileName, _.payload.fileName) == fileName).get

  /** How many rows of the table belong to the test's repository */
  private def rowCount(table: String): Int = testApp.txManager.asReadOnly {
    query(s"SELECT count(*) AS n FROM $table WHERE repository_id = ?", testContext.repository.persistedId)
      .head("n")
      .toString
      .toInt
  }

  /** Every face file of the test's repository */
  private def faceFiles: List[Path] =
    val facesDir =
      Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, testContext.repository.persistedId, Const.DataStore.FACES)
    if !Files.exists(facesDir) then Nil
    else Files.walk(facesDir).iterator.asScala.filter(Files.isRegularFile(_)).toList

  /** A staged copy of an import fixture, its checksum the file's own, in the test's context */
  private def staged(relPath: String): (AssetWithData, PipelineContext) =
    (
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.getImportAsset(relPath)),
      PipelineContext(testContext.repository, Some(testContext.user)))

  /** The asset a single run imported, or the test fails with the cause it was dropped for */
  private def imported(results: Seq[TAssetOrInvalidWithContext]): Asset = results match {
    case Seq((Left(asset), _)) => asset
    case other => fail(s"Expected one imported asset: $other")
  }

  /** The asset a single run dropped */
  private def dropped(results: Seq[TAssetOrInvalidWithContext]): InvalidAsset = results match {
    case Seq((Right(invalid), _)) => invalid
    case other => fail(s"Expected one dropped asset: $other")
  }

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

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
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

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
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
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))

    // Every offer is made before any is awaited
    val offers =
      (1 to batchSize).map(_ => testApp.service.importPipeline.addToQueue((testContext.makeAssetWithData(), pipelineContext)))
    Await.result(Future.sequence(offers)(implicitly, scala.concurrent.ExecutionContext.global), 30.seconds)

    eventually {
      importedCount() shouldBe batchSize
    }
  }

  test("Shutdown waits for the assets the queue has accepted") {

    /**
     * Setup:
     *
     * An import queue of the test's own (a new `ImportPipelineService`), six assets over staged random images offered to it,
     * every offer accepted, then the queue shut down.
     *
     * Assertions:
     *
     * By the time shutdown returns, without any further waiting, all six are pipeline-processed.
     */
    val batchSize = 6
    val pipeline = ImportPipelineService(testApp)
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))

    val offers = (1 to batchSize).map(_ => pipeline.addToQueue((testContext.makeAssetWithData(), pipelineContext)))
    Await.result(Future.sequence(offers)(implicitly, scala.concurrent.ExecutionContext.global), 30.seconds)

    pipeline.shutdown()
    importedCount() shouldBe batchSize
  }

  test("An offer to a shut-down import queue fails, and the upload's staged file is deleted") {

    /**
     * Setup:
     *
     * An import queue of the test's own, shut down, then an asset over a staged random image offered to it.
     *
     * Assertions:
     *
     * The offer fails with `QueueRefusedException`, and the staged file is gone.
     */
    val pipeline = ImportPipelineService(testApp)
    pipeline.shutdown()

    val upload = testContext.makeAssetWithData()
    intercept[QueueRefusedException] {
      Await.result(pipeline.addToQueue((upload, PipelineContext(testContext.repository, Some(testContext.user)))), 30.seconds)
    }
    Files.exists(upload.path) shouldBe false
  }

  test("With the restart on, a queue whose stream fails restarts it and imports what is offered after") {

    /**
     * Setup:
     *
     * A queue of the test's own, restart on, over a flow that fails its stream on one element. That element offered, then a good
     * one, offered again until it comes out of the flow (an offer made during the restart's backoff is refused, and one the
     * failing stream took is lost).
     *
     * Assertions:
     *
     * The good element comes out of the flow, from the restarted stream.
     */
    val passed = ConcurrentLinkedQueue[String]()
    val pipeline = failingQueue(restartOnFailure = true, passed)

    Await.result(pipeline.offer("bad"), 10.seconds)
    eventually {
      Try(Await.result(pipeline.offer("good"), 10.seconds))
      passed.asScala should contain("good")
    }
    pipeline.shutdown()
  }

  test("With the restart off, a queue whose stream fails stays down and refuses what is offered after") {

    /**
     * Setup:
     *
     * A queue of the test's own, restart off, over a flow that fails its stream on one element. That element offered, then a good
     * one, offered again until an offer is refused (one the failing stream took is lost).
     *
     * Assertions:
     *
     * The good element's offer fails with `QueueRefusedException`, and nothing came out of the flow.
     */
    val passed = ConcurrentLinkedQueue[String]()
    val pipeline = failingQueue(restartOnFailure = false, passed)

    Await.result(pipeline.offer("bad"), 10.seconds)
    eventually {
      intercept[QueueRefusedException](Await.result(pipeline.offer("good"), 10.seconds))
    }
    passed shouldBe empty
    pipeline.shutdown()
  }

  test("Shutdown during a restart's backoff returns at once") {

    /**
     * Setup:
     *
     * A queue of the test's own, restart on and a 30-second drain limit, over a flow that fails its stream on one element. That
     * element offered, then good ones until one is refused, which means the stream is waiting out its backoff; then the queue
     * shut down.
     *
     * Assertions:
     *
     * Shutdown returns well within the drain limit, rather than waiting for a restarted stream to drain.
     */
    val pipeline = failingQueue(restartOnFailure = true, ConcurrentLinkedQueue[String]())

    Await.result(pipeline.offer("bad"), 10.seconds)
    eventually {
      intercept[QueueRefusedException](Await.result(pipeline.offer("good"), 10.seconds))
    }

    val started = System.nanoTime()
    pipeline.shutdown()
    (System.nanoTime() - started).nanos should be < 5.seconds
  }

  test("A stage does its work on the import dispatcher, in the context of the asset's repository") {

    /**
     * Setup:
     *
     * A stage built with `PipelineUtils.stage` whose work records the thread it runs on and the repository in the request
     * context, run over one asset of a second repository while the test's context is its own repository.
     *
     * Assertions:
     *
     * The work ran on a thread of the import dispatcher, with the second repository as the context repository.
     */
    val otherRepository = testContext.persistRepository()
    val dataAsset = testContext.makeAssetWithData(Some(testContext.makeAsset(repository = Some(otherRepository))))

    var thread = ""
    var repositoryId = ""
    val recording = PipelineUtils.stage("Recording", parallelism = 1) {
      dataAsset =>
        thread = Thread.currentThread.getName
        repositoryId = RequestContext.getRepository.persistedId
        Left(dataAsset)
    }(using testApp.importDispatcher)

    val element: TDataAssetOrInvalidWithContext = (Left(dataAsset), PipelineContext(otherRepository, Some(testContext.user)))
    Await.result(
      Source.single(element).via(recording).runWith(Sink.seq)(using matFromSystem(using testApp.actorSystem)),
      30.seconds)

    thread should include("import-dispatcher")
    repositoryId shouldBe otherRepository.persistedId
  }

  test("An image that cannot be decoded is dropped, and the asset behind it is imported") {

    /**
     * Setup:
     *
     * Bytes no image reader takes, staged under a JPEG asset, followed by an asset over a staged random image, in one stream.
     *
     * Assertions:
     *
     * The first comes out dropped with `ImageException` from the metadata stage and its staged file is deleted; the photo behind
     * it is imported.
     */
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val undecodable = notAnImage()
    val photo = testContext.makeAssetWithData()

    val pipelineRes = runPipeline((undecodable, pipelineContext), (photo, pipelineContext))
    pipelineRes should have size 2

    pipelineRes.head match {
      case (Right(invalid), _) => invalid.cause.get shouldBe a[ImageException]
      case _ => fail("Expected the undecodable image to be dropped")
    }
    Files.exists(undecodable.path) shouldBe false

    pipelineRes(1) match {
      case (Left(asset), _) => testApp.service.asset.getById(asset.persistedId).isPipelineProcessed shouldBe true
      case (Right(invalid), _) => fail(s"Expected the photo to be imported: ${invalid.cause}")
    }
  }

  test("A queued image that cannot be decoded is dropped, and the queue imports the asset behind it") {

    /**
     * Setup:
     *
     * Bytes no image reader takes, staged under a JPEG asset, offered to the long-running import queue, then an asset over a
     * staged random image.
     *
     * Assertions:
     *
     * The photo ends up pipeline-processed, polled for up to a minute, so the queue outlived the undecodable image, whose staged
     * file is deleted.
     */
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val undecodable = notAnImage()

    Await.result(testApp.service.importPipeline.addToQueue((undecodable, pipelineContext)), 30.seconds)
    Await.result(testApp.service.importPipeline.addToQueue((testContext.makeAssetWithData(), pipelineContext)), 30.seconds)

    eventually {
      importedCount() shouldBe 1
      Files.exists(undecodable.path) shouldBe false
    }
  }

  test("An asset whose file cannot be stored is dropped, and the stream goes on importing") {

    /**
     * Setup:
     *
     * A second repository whose `files` directory is a plain file, so storing any of its assets fails. An asset over a staged
     * random image for it, followed in the same stream by one for the test's own repository.
     *
     * Assertions:
     *
     * The first comes out dropped with `StorageException` from the file store stage and its staged file is deleted; the second is
     * imported.
     */
    val repository = testContext.repository
    val brokenRepository = testContext.persistRepository()
    breakFileStore(brokenRepository)
    // Adding a repository makes it the context one; the photo is read back from the test's own
    switchContextRepo(repository)

    val unstorable = testContext.makeAssetWithData(Some(testContext.makeAsset(repository = Some(brokenRepository))))
    val photo = testContext.makeAssetWithData(Some(testContext.makeAsset(repository = Some(repository))))

    val pipelineRes = runPipeline(
      (unstorable, PipelineContext(brokenRepository, Some(testContext.user))),
      (photo, PipelineContext(repository, Some(testContext.user))))
    pipelineRes should have size 2

    // Each repository has its own substream, so the results come in either order
    resultFor(pipelineRes, unstorable.asset.fileName) match {
      case (Right(invalid), _) => invalid.cause.get shouldBe a[StorageException]
      case _ => fail("Expected the asset of the broken repository to be dropped")
    }
    Files.exists(unstorable.path) shouldBe false

    resultFor(pipelineRes, photo.asset.fileName) match {
      case (Left(asset), _) => testApp.service.asset.getById(asset.persistedId).isPipelineProcessed shouldBe true
      case (Right(invalid), _) => fail(s"Expected the photo to be imported: ${invalid.cause}")
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

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
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

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
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
     * The pipeline completes with the clip dropped with `VideoException` from the preview stage; its staged file, its row and its
     * stored file are deleted, and it has no preview. The photo behind it is imported.
     */
    val clip =
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.undecodable.toFile))
    val photo = testContext.makeAssetWithData()

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source(List(clip, photo)).map((_, pipelineContext))

    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] =
      testApp.service.importPipeline.run(source, AssetSeqOutputSink())

    val pipelineRes = Await.result(pipelineResFuture, Duration.Inf)
    pipelineRes should have size 2

    // The Preview has no frame to take, which drops the clip rather than failing the pipeline
    val droppedClip = pipelineRes.head match {
      case (Right(invalid), _) =>
        invalid.cause.get shouldBe a[VideoException]
        invalid.payload
      case _ => fail("Expected the clip to be dropped")
    }
    Files.exists(clip.path) shouldBe false

    // The clip was persisted and stored before its preview failed; its discard takes the row and the stored file
    testApp.service.asset.queryAll(new Query(Map(FieldConst.ID -> droppedClip.persistedId))).total shouldBe 0
    Files.exists(testApp.service.fileStore.assetFile(droppedClip.persistedId)) shouldBe false
    intercept[NotFoundException](testApp.service.asset.getPreview(droppedClip.persistedId))

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
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
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

  test("A dropped import leaves no row, face or file behind, and the person it started is gone") {

    /**
     * Setup:
     *
     * The repository's `files` directory replaced by a plain file, then `people/affleck.jpg` imported, so the import stores the
     * face it recognizes, which starts a Person, before the file store stage fails.
     *
     * Assertions:
     *
     * The asset is dropped with `StorageException`. Afterwards the repository has no asset, face, person or Search document row
     * and no face file, and the staged file is gone.
     */
    breakFileStore(testContext.repository)
    val photo = staged("people/affleck.jpg")

    dropped(runPipeline(photo)).cause.get shouldBe a[StorageException]

    rowCount("asset") shouldBe 0
    rowCount("face") shouldBe 0
    rowCount("person") shouldBe 0
    rowCount("search_document") shouldBe 0
    faceFiles shouldBe empty
    Files.exists(photo._1.path) shouldBe false
  }

  test("A dropped import gives back the face it added to a known person, hidden or not") {

    /**
     * Setup:
     *
     * `people/meme-ben2.png` imported, which starts a Person with one Face, and the Person hidden (hidden people stay matchable).
     * Then the repository's `files` directory replaced by a plain file, and `people/meme-ben3.png`, the same person, imported, so
     * the import adds a second Face to the Person before the file store stage fails.
     *
     * Assertions:
     *
     * The second import is dropped with `StorageException`. The Person stays with one Face, the first import's, which is still
     * its cover, and that Face's files are the only face files left.
     */
    val first = imported(runPipeline(staged("people/meme-ben2.png")))
    val (face, person) = testApp.service.person.getAssetFacesWithPeople(first.persistedId).head
    testApp.service.person.setVisibility(person, isHidden = true)
    val filesOfFirstFace = faceFiles

    breakFileStore(testContext.repository)
    dropped(runPipeline(staged("people/meme-ben3.png"))).cause.get shouldBe a[StorageException]

    val after: Person = testApp.service.person.getPersonById(person.persistedId)
    after.numOfFaces shouldBe 1
    after.coverFaceId shouldBe Some(face.persistedId)
    rowCount("face") shouldBe 1
    faceFiles should contain theSameElementsAs filesOfFirstFace
  }

  test("A file dropped for a reason that has passed imports when it is uploaded again") {

    /**
     * Setup:
     *
     * One random image staged twice from the same bytes, so both copies have its checksum. The first copy imported while the
     * repository's `files` directory is a plain file; the directory put back; the second copy imported.
     *
     * Assertions:
     *
     * The first copy is dropped with `StorageException`; the second is imported rather than rejected as a duplicate of it.
     */
    val bytes = IntegrationTestUtil.generateRandomImagBytesBgr(dimensions = 150)
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    def upload(): (AssetWithData, PipelineContext) =
      (testApp.service.library.stagedFileToAsset("upload.png", testApp.service.staging.stage(bytes)), pipelineContext)

    breakFileStore(testContext.repository)
    dropped(runPipeline(upload())).cause.get shouldBe a[StorageException]

    restoreFileStore(testContext.repository)
    val asset = imported(runPipeline(upload()))
    testApp.service.asset.getById(asset.persistedId).isPipelineProcessed shouldBe true
  }

  test("A dropped duplicate leaves the asset it duplicates untouched") {

    /**
     * Setup:
     *
     * `people/affleck.jpg` imported, which stores its file, its preview and a Face of a new Person; then the same file imported
     * again.
     *
     * Assertions:
     *
     * The second import is dropped with `DuplicateException`. The first asset keeps its row, its file, its preview, its Face and
     * the Face's files, and its Person keeps one Face.
     */
    val original = imported(runPipeline(staged("people/affleck.jpg")))
    val (face, person) = testApp.service.person.getAssetFacesWithPeople(original.persistedId).head
    val filesOfOriginal = faceFiles

    dropped(runPipeline(staged("people/affleck.jpg"))).cause.get shouldBe a[DuplicateException]

    testApp.service.asset.getById(original.persistedId).isPipelineProcessed shouldBe true
    Files.exists(testApp.service.fileStore.assetFile(original.persistedId)) shouldBe true
    testApp.service.asset.getPreview(original.persistedId).data should not be empty
    testApp.service.person.getAssetFacesWithPeople(original.persistedId).map(_._1.persistedId) shouldBe List(face.persistedId)
    testApp.service.person.getPersonById(person.persistedId).numOfFaces shouldBe 1
    faceFiles should contain theSameElementsAs filesOfOriginal
  }

  test("More photos of one person than the import has threads, imported at once, are one Person", Slow) {

    /**
     * Setup:
     *
     * Two more distinct photos of the same face than the import dispatcher has threads (`people/affleck.jpg` scaled down, each on
     * a canvas one pixel wider than the last), imported in one stream, so their faces are detected at once.
     *
     * Assertions:
     *
     * Every photo is imported, and the repository has one Person, with a Face from each: the faces were matched one at a time, in
     * upload order, the first starting the Person and every other joining it.
     */
    val count = testApp.importParallelism + 2
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))

    val photos = (0 until count).map {
      i =>
        val png = new ByteArrayOutputStream()
        ImageIO.write(portraitFrame(extraWidth = i), "png", png)
        (
          testApp.service.library.stagedFileToAsset(s"affleck-$i.png", testApp.service.staging.stage(png.toByteArray)),
          pipelineContext)
    }

    val pipelineRes = runPipeline(photos*)
    pipelineRes.collect { case (Right(invalid), _) => invalid.cause } shouldBe empty

    rowCount("person") shouldBe 1
    val person = testApp.service.person.getAll.head
    person.numOfFaces shouldBe count
  }

  test("An image imports with the dimensions its header gives") {

    /**
     * Setup:
     *
     * A JPEG and a PNG (`images/1.jpg`, `images/3.png`) imported.
     *
     * Assertions:
     *
     * Each is stored with the width and height of the image as `ImageIO` decodes it.
     */
    List("images/1.jpg", "images/3.png").foreach {
      relPath =>
        val (dataAsset, pipelineContext) = staged(relPath)
        val decoded = ImageIO.read(dataAsset.path.toFile)

        val asset = testApp.service.asset.getById(imported(runPipeline((dataAsset, pipelineContext))).persistedId)
        (asset.width, asset.height) shouldBe (decoded.getWidth, decoded.getHeight)
    }
  }

  test("An image whose header reads but whose data does not decode is dropped as undecodable") {

    /**
     * Setup:
     *
     * A random PNG whose image data is replaced by bytes that do not inflate, its header and chunk checksums intact.
     *
     * Assertions:
     *
     * It is dropped with `ImageException`, the cause the user is shown as "Cannot decode image".
     */
    val corrupt = pngWithUndecodableData(IntegrationTestUtil.generateRandomImagBytesBgr(dimensions = 150))
    val upload = testApp.service.library.stagedFileToAsset("corrupt.png", testApp.service.staging.stage(corrupt))

    dropped(runPipeline((upload, PipelineContext(testContext.repository, Some(testContext.user))))).cause.get shouldBe a[
      ImageException]
  }

  /** The PNG with the payload of its first IDAT chunk overwritten by bytes zlib refuses, and that chunk's CRC recomputed */
  private def pngWithUndecodableData(png: Array[Byte]): Array[Byte] =
    val bytes = png.clone()
    val idat = bytes.indexOfSlice("IDAT".getBytes(StandardCharsets.US_ASCII))
    val length = ByteBuffer.wrap(bytes, idat - 4, 4).getInt
    java.util.Arrays.fill(bytes, idat + 4, idat + 4 + length, 0xff.toByte)

    val crc = new CRC32()
    crc.update(bytes, idat, 4 + length)
    ByteBuffer.wrap(bytes, idat + 4 + length, 4).putInt(crc.getValue.toInt)
    bytes

  test("A still GIF imports with its dimensions and a preview, and no duration") {

    /**
     * Setup:
     *
     * A 64-pixel still GIF (`images/2.gif`), a format the bundled OpenCV has no codec for, imported.
     *
     * Assertions:
     *
     * It is imported at 64 by 64 with no duration, and its preview decodes as an image filling the square preview box; being
     * square, nothing of it is transparent, so the WebP carries no alpha plane: three channels.
     */
    val asset = testApp.service.asset.getById(imported(runPipeline(staged("images/2.gif"))).persistedId)
    (asset.width, asset.height) shouldBe (64, 64)
    asset.durationMs shouldBe None

    val preview = IntegrationTestUtil.decodePreview(testApp.service.asset.getPreview(asset.persistedId).data)
    val box = Const.AssetView.PREVIEW_BOX_PIXELS
    (preview.cols, preview.rows, preview.channels) shouldBe (box, box, 3)
  }

  test("An animated GIF imports with its playing time as its duration and a preview, and no face is looked for in it") {

    /**
     * Setup:
     *
     * A frame of `people/affleck.jpg` saved as a still GIF and imported, which starts a Person. Then an animated GIF of three
     * frames of the same face, shown for 0, 20 and 30 hundredths of a second, imported.
     *
     * Assertions:
     *
     * The still GIF has a Face, so the frames hold a face that is found and recognized, and, decoded through the ImageIO
     * fallback, it is measured: it has an Image quality and a perceptual hash. The animated GIF is imported with a duration of
     * 600 ms (a frame without a delay plays for 100 ms, as browsers show it) and a preview, and has no Face: its frames are not
     * searched, and the Person keeps one Face. Nor is it measured: no Image quality, no hash.
     */
    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    def gif(name: String, frames: Seq[BufferedImage], delaysCs: Seq[Int]): (AssetWithData, PipelineContext) =
      (
        testApp.service.library.stagedFileToAsset(name, testApp.service.staging.stage(gifBytes(frames, delaysCs))),
        pipelineContext)

    val still = imported(runPipeline(gif("still.gif", Seq(portraitFrame()), Seq(0))))
    testApp.service.person.getAssetFacesWithPeople(still.persistedId) should have size 1
    val storedStill: Asset = testApp.service.asset.getById(still.persistedId)
    storedStill.imageQuality should not be None
    storedStill.perceptualHash should not be None

    val animated = testApp.service.asset.getById(
      imported(
        runPipeline(gif("animated.gif", Seq(portraitFrame(), portraitFrame(1), portraitFrame(2)), Seq(0, 20, 30)))).persistedId)
    animated.durationMs shouldBe Some(600L)
    animated.imageQuality shouldBe None
    animated.perceptualHash shouldBe None
    testApp.service.asset.getPreview(animated.persistedId).data should not be empty
    testApp.service.person.getAssetFacesWithPeople(animated.persistedId) shouldBe empty
    testApp.service.person.getAll.map(_.numOfFaces) shouldBe List(1)
  }

  test("A GIF's transparency carries into its preview") {

    /**
     * Setup:
     *
     * A 100-pixel square GIF whose left half is opaque red and whose right half is the palette's transparent color, imported.
     *
     * Assertions:
     *
     * Its preview, which fills the 200-pixel box, is opaque red on the left, within the lossy encoding's tolerance, and
     * transparent on the right, exactly.
     */
    val palette = new IndexColorModel(8, 2, Array[Byte](0xff.toByte, 0), Array[Byte](0, 0), Array[Byte](0, 0), 1)
    val image = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_INDEXED, palette)
    for x <- 0 until 100; y <- 0 until 100 do image.getRaster.setSample(x, y, 0, if x < 50 then 0 else 1)
    val gif = new ByteArrayOutputStream()
    ImageIO.write(image, "gif", gif)

    val upload = testApp.service.library.stagedFileToAsset("half.gif", testApp.service.staging.stage(gif.toByteArray))
    val asset = imported(runPipeline((upload, PipelineContext(testContext.repository, Some(testContext.user)))))

    val preview = IntegrationTestUtil.decodePreview(testApp.service.asset.getPreview(asset.persistedId).data)
    // A pixel's samples are its blue, green, red and alpha
    val left = preview.get(100, 50)
    left(3) shouldBe 255.0
    left(0) shouldBe 0.0 +- 8
    left(1) shouldBe 0.0 +- 8
    left(2) shouldBe 255.0 +- 8
    preview.get(100, 150)(3) shouldBe 0.0
  }

  /** The element as a file of the Import: a pending Import file by its file name, which the element carries as its origin */
  private def asFileOf(anImport: Import, dataAsset: AssetWithData): (AssetWithData, ImportFile) =
    val file = testApp.service.imports.addFile(anImport.persistedId, dataAsset.asset.fileName, dataAsset.asset.sizeBytes)
    (dataAsset.copy(origin = Some(ImportOrigin(file.persistedId))), file)

  /** The Import file as stored now */
  private def storedFile(file: ImportFile): ImportFile =
    testApp.service.imports.files(file.importId).find(_.id == file.id).get

  /** A staged random image under an asset with the checksum of `existing`, as a second copy of its file would arrive */
  private def copyOf(existing: Asset): AssetWithData =
    testContext.makeAssetWithData(Some(testContext.makeAsset().copy(checksum = existing.checksum)))

  test("The files of an Import are settled by what the pipeline made of them, and the Import's counters agree with the Library") {

    /**
     * Setup:
     *
     * An asset already in the Library, in Triage. An upload's Import of four files, marked complete as its last file is sent, run
     * through the import pipeline in one stream, each carrying its Import file as its origin: a new image, a second copy of the
     * asset already there, a file of a made-up media type, and bytes no image reader takes under a JPEG name.
     *
     * Assertions:
     *
     * The pipeline imports the first and drops the other three, and each Import file is settled accordingly: the first imported,
     * pointing at the asset it became, which is complete in the Library; the second a duplicate, pointing at the asset already
     * there; the third unsupported and the fourth failed, each with its reason. The Import counts one file of each outcome and
     * none pending, and is closed. The Library holds two completed assets: the one it had and the one imported.
     */
    val existing = testContext.persistAsset(isTriaged = true)
    val context = PipelineContext(testContext.repository, Some(testContext.user))
    val imports = testApp.service.imports
    val opened = imports.open(ImportSource.Upload)

    val (photo, photoFile) = asFileOf(opened, testContext.makeAssetWithData())
    val (copy, copyFile) = asFileOf(opened, copyOf(existing))
    val (document, documentFile) =
      asFileOf(
        opened,
        testContext.makeAssetWithData(Some(testContext.makeAsset().copy(assetType = AssetType("bad", "type", "mime")))))
    val (broken, brokenFile) = asFileOf(opened, notAnImage())
    imports.close(opened.persistedId)
    imports.getById(opened.persistedId).closedAt shouldBe None

    val results = runPipeline(List(photo, copy, document, broken).map((_, context))*)

    val importedAsset = resultFor(results, photo.asset.fileName)._1.left.getOrElse(fail("Expected the new image to import"))
    results.count(_._1.isRight) shouldBe 3

    def settled(file: ImportFile) =
      val read = storedFile(file)
      (read.outcome, read.reason, read.assetId)

    settled(photoFile) shouldBe (ImportOutcome.Imported, None, importedAsset.id)
    testApp.service.asset.getById(importedAsset.persistedId).isPipelineProcessed shouldBe true
    settled(copyFile) shouldBe (ImportOutcome.Duplicate, Some("Triage"), existing.id)
    settled(documentFile) shouldBe (ImportOutcome.Unsupported, Some("Unsupported media type"), None)
    settled(brokenFile) shouldBe (ImportOutcome.Failed, Some("Cannot decode image"), None)

    val closed = imports.getById(opened.persistedId)
    (closed.pending, closed.imported, closed.duplicate, closed.unsupported, closed.failed) shouldBe (0, 1, 1, 1, 1)
    closed.closedAt should not be empty
    importedCount() shouldBe 2
  }

  test("A duplicate's Import file names where the asset already in the Library is") {

    /**
     * Setup:
     *
     * Three assets already in the Library: one in Triage, one in the folder 2024 under the folder Travel, and one in the root
     * folder. An Import of a second copy of each, run through the import pipeline.
     *
     * Assertions:
     *
     * Every copy is dropped as a duplicate, and its Import file is a duplicate pointing at the asset already there, with where
     * that asset is as its reason: "Triage", the folder path "Travel/2024", and "Top level" for the root folder. The Import
     * counts three duplicates.
     */
    val travel = testApp.service.folder.add("Travel")
    val year = testApp.service.folder.add("2024", travel.id)
    val inTriage = testContext.persistAsset(isTriaged = true)
    val inFolder = testContext.persistAsset(folder = Some(year))
    val inRoot = testContext.persistAsset()

    val context = PipelineContext(testContext.repository, Some(testContext.user))
    val opened = testApp.service.imports.open(ImportSource.Upload)
    val copies = List(inTriage, inFolder, inRoot).map(existing => asFileOf(opened, copyOf(existing)))

    val results = runPipeline(copies.map((copy, _) => (copy, context))*)

    results.map(_._1.fold(_ => None, _.cause.map(_.getClass))) shouldBe List.fill(3)(Some(classOf[DuplicateException]))
    copies.map((_, file) => storedFile(file)).map(file => (file.outcome, file.reason, file.assetId)) shouldBe List(
      (ImportOutcome.Duplicate, Some("Triage"), inTriage.id),
      (ImportOutcome.Duplicate, Some("Travel/2024"), inFolder.id),
      (ImportOutcome.Duplicate, Some("Top level"), inRoot.id)
    )
    testApp.service.imports.getById(opened.persistedId).duplicate shouldBe 3
  }

  test("A file that fails in a stage after its asset is persisted is settled failed, and leaves no asset") {

    /**
     * Setup:
     *
     * A repository whose file store cannot store a file, and an Import of one image run through the import pipeline there.
     *
     * Assertions:
     *
     * The file is dropped at the file store with a storage error, its Import file is failed with that as its reason and points at
     * no asset, and the Import counts it failed. Nothing of the asset is left in the repository.
     */
    val repository = testContext.repository
    val opened = testApp.service.imports.open(ImportSource.Upload)
    val (photo, file) = asFileOf(opened, testContext.makeAssetWithData())
    breakFileStore(repository)

    val result =
      try dropped(runPipeline((photo, PipelineContext(repository, Some(testContext.user)))))
      finally restoreFileStore(repository)

    result.cause.get shouldBe a[StorageException]
    val settled = storedFile(file)
    (settled.outcome, settled.reason, settled.assetId) shouldBe (ImportOutcome.Failed, Some("Storage error"), None)
    testApp.service.imports.getById(opened.persistedId).failed shouldBe 1
    rowCount("asset") shouldBe 0
  }

  test("A file whose Import file cannot be settled is not imported") {

    /**
     * Setup:
     *
     * An image run through the import pipeline with an origin that names no Import file.
     *
     * Assertions:
     *
     * The completion, which settles the Import file in the transaction that completes the asset, fails as a whole: the file is
     * dropped with the Import file not found, and no asset, complete or not, is left in the repository.
     */
    val orphan = testContext.makeAssetWithData().copy(origin = Some(ImportOrigin("no-such-import-file")))

    val result = dropped(runPipeline((orphan, PipelineContext(testContext.repository, Some(testContext.user)))))

    result.cause.get shouldBe a[NotFoundException]
    rowCount("asset") shouldBe 0
  }

  /**
   * A staged random image as an upload stages it (triaged, in no folder), a file of the Import under `relPath`: its origin
   * carries the folder names of the path
   */
  private def asFileAt(anImport: Import, relPath: String, checksum: Option[String] = None): (AssetWithData, ImportFile) = {
    val names = relPath.split("/").toList
    val asset = testContext.makeAsset(filename = names.last, isTriaged = true)
    val dataAsset = testContext.makeAssetWithData(Some(checksum.fold(asset)(sum => asset.copy(checksum = sum))))
    val file = testApp.service.imports.addFile(anImport.persistedId, relPath, dataAsset.asset.sizeBytes)
    (dataAsset.copy(origin = Some(ImportOrigin(file.persistedId, names.init))), file)
  }

  /** The asset an Import file points at, as stored now */
  private def assetOf(file: ImportFile): Asset = testApp.service.asset.getById(storedFile(file).assetId.get)

  /** Where an asset is: the names of its folder's path, the top-level folder first */
  private def placeOf(asset: Asset): List[String] = testApp.service.folder.getPath(asset.folderId).map(_.name)

  /** The names of the repository's folders, live or recycled, the root left out */
  private def folderNames: Set[String] =
    testApp.service.folder.getAll.filterNot(folder => folder.persistedId == folder.parentId).map(_.name).toSet

  private def stat(dimension: String): Long = testApp.service.stats.getStats.getStatValue(dimension)

  test(
    "A kept tree lands under the destination, in the folders that exist, live or recycled, and new ones where an asset lands") {

    /**
     * Setup:
     *
     * A folder Clients holding the live folder Ceremony, with an asset in it, and the folder Party, recycled by a delete. An
     * upload's Import with Clients as its destination, of seven files run through the import pipeline, each carrying the folders
     * of its path: an image at the top, one in Ceremony, one in Party, one in Portraits/Group, a second copy of the image in
     * Ceremony under Copies, a second copy of the asset already in Ceremony under Old, and a file of a made-up media type under
     * Docs.
     *
     * Assertions:
     *
     * The four images are imported, sorted (not triaged, in no Sequence) and each in the folder its path names under Clients: the
     * top one in Clients itself, one in the Ceremony that was there, one in Party, restored, and one in Portraits/Group, both
     * created. The Library counts them as sorted and none in Triage.
     *
     * Edge cases:
     *
     * The two copies are duplicates and the made-up type unsupported, and no folder is created for any of them: Copies, Old and
     * Docs do not exist. Each duplicate names where the asset it matches is: the copy of this Import's own first file the folder
     * that file landed in, the copy of the asset already there its folder.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val ceremony = folders.add("Ceremony", clients.id)
    val party = folders.add("Party", clients.id)
    val existing = testContext.persistAsset(folder = Some(ceremony))
    testApp.service.library.deleteFolderById(party.persistedId)

    val opened = testApp.service.imports.open(ImportSource.Upload, name = Some("Wedding"), destFolderId = clients.id)
    val (top, topFile) = asFileAt(opened, "top.jpg")
    val (vows, vowsFile) = asFileAt(opened, "Ceremony/vows.jpg")
    val (dance, danceFile) = asFileAt(opened, "Party/dance.jpg")
    val (group, groupFile) = asFileAt(opened, "Portraits/Group/group.jpg")
    val (vowsCopy, vowsCopyFile) = asFileAt(opened, "Copies/vows-copy.jpg", checksum = Some(vows.asset.checksum))
    val (old, oldFile) = asFileAt(opened, "Old/existing.jpg", checksum = Some(existing.checksum))
    val (document, documentFile) = {
      val (staged, file) = asFileAt(opened, "Docs/readme.pdf")
      (staged.copy(asset = staged.asset.copy(assetType = AssetType("bad", "type", "mime"))), file)
    }
    testApp.service.imports.close(opened.persistedId)
    val sortedBefore = stat(Stats.SORTED_ASSETS)

    val context = PipelineContext(testContext.repository, Some(testContext.user))
    runPipeline(List(top, vows, dance, group, vowsCopy, old, document).map((_, context))*)

    val landed = List(topFile, vowsFile, danceFile, groupFile).map(assetOf)
    landed.map(placeOf) shouldBe List(
      List("Clients"),
      List("Clients", "Ceremony"),
      List("Clients", "Party"),
      List("Clients", "Portraits", "Group"))
    landed.map(asset => (asset.isTriaged, asset.sequenceId, asset.isPipelineProcessed)) shouldBe List.fill(4)((false, None, true))
    assetOf(vowsFile).folderId shouldBe ceremony.persistedId
    assetOf(danceFile).folderId shouldBe party.persistedId
    folders.getById(party.persistedId).isRecycled shouldBe false
    stat(Stats.SORTED_ASSETS) shouldBe sortedBefore + 4
    stat(Stats.TRIAGE_ASSETS) shouldBe 0

    folderNames shouldBe Set("Clients", "Ceremony", "Party", "Portraits", "Group")
    List(vowsCopyFile, oldFile, documentFile)
      .map(storedFile)
      .map(file => (file.outcome, file.reason, file.assetId)) shouldBe List(
      (ImportOutcome.Duplicate, Some("Clients/Ceremony"), assetOf(vowsFile).id),
      (ImportOutcome.Duplicate, Some("Clients/Ceremony"), existing.id),
      (ImportOutcome.Unsupported, Some("Unsupported media type"), None)
    )
    val closed = testApp.service.imports.getById(opened.persistedId)
    (closed.pending, closed.imported, closed.duplicate, closed.unsupported, closed.failed) shouldBe (0, 4, 2, 1, 0)
    closed.closedAt should not be empty
  }

  /**
   * Burst frame `n` staged as an upload stages it, with the camera's model and clock seeded as `BurstFrames.importFrame` seeds
   * them
   */
  private def stagedFrame(n: Int): AssetWithData = {
    val staged = testApp.service.library.stagedFileToAsset(s"burst-$n.jpg", testApp.service.staging.stage(BurstFrames.frame(n)))
    val metadata = ExtractedMetadata()
    metadata.addValue("Exif IFD0", "Model", BurstFrames.MODEL)
    metadata.addValue("Exif SubIFD", "Date/Time Original", BurstFrames.takenAt(n))
    staged.copy(asset = staged.asset.copy(extractedMetadata = metadata))
  }

  test("The files of an upload that skips Triage land in its destination itself, sorted, with no Sequence") {

    /**
     * Setup:
     *
     * A folder Sorted. An upload's Import with Sorted as its destination, of two frames of one burst under their bare names, run
     * through the import pipeline; then an Import with no destination of the next two frames of the same burst.
     *
     * Assertions:
     *
     * The first two frames are imported into Sorted itself, not triaged and in no Sequence, and the Library counts two sorted
     * assets with their bytes and none in Triage. No folder is created.
     *
     * Edge cases:
     *
     * The next two frames, uploaded to Triage, are triaged, in no folder, and share a Sequence, which the first two, being
     * sorted, do not join: the frames are a burst, and it is the destination that kept the first two out of a Sequence. The
     * Library then counts two sorted and two in Triage.
     */
    val sorted = testApp.service.folder.add("Sorted")
    val context = PipelineContext(testContext.repository, Some(testContext.user))

    val skipping = testApp.service.imports.open(ImportSource.Upload, destFolderId = sorted.id)
    val skipped = List(0, 1).map(n => asFileOf(skipping, stagedFrame(n)))
    runPipeline(skipped.map((frame, _) => (frame, context))*)

    val inSorted = skipped.map((_, file) => assetOf(file))
    inSorted.map(asset => (asset.folderId, asset.isTriaged, asset.sequenceId)) shouldBe List.fill(2)(
      (sorted.persistedId, false, None))
    stat(Stats.SORTED_ASSETS) shouldBe 2
    stat(Stats.SORTED_BYTES) shouldBe inSorted.map(_.sizeBytes).sum
    stat(Stats.TRIAGE_ASSETS) shouldBe 0
    folderNames shouldBe Set("Sorted")

    val triaging = testApp.service.imports.open(ImportSource.Upload)
    val triaged = List(2, 3).map(n => asFileOf(triaging, stagedFrame(n)))
    runPipeline(triaged.map((frame, _) => (frame, context))*)

    val inTriage = triaged.map((_, file) => assetOf(file))
    inTriage.map(asset => (asset.folderId, asset.isTriaged)) shouldBe List.fill(2)(("", true))
    inTriage.head.sequenceId should not be None
    inTriage.map(_.sequenceId).distinct.size shouldBe 1
    skipped.map((_, file) => assetOf(file).sequenceId) shouldBe List(None, None)
    (stat(Stats.SORTED_ASSETS), stat(Stats.TRIAGE_ASSETS)) shouldBe (2, 2)
    testApp.service.imports.getById(skipping.persistedId).imported shouldBe 2
  }

  test("An upload whose destination is recycled meanwhile fails its remaining files") {

    /**
     * Setup:
     *
     * An upload's Import with the folder Clients as its destination. Its first file, in the folder Ceremony of the tree, is run
     * through the import pipeline; then Clients is deleted, which recycles it with what it holds; then its second file, in the
     * folder Party, is run through.
     *
     * Assertions:
     *
     * The first file is imported into Clients/Ceremony. The second is dropped at its completion, and its Import file is failed,
     * saying that the destination folder is in the Trash, pointing at no asset. The Import counts one imported and one failed.
     *
     * Edge cases:
     *
     * Nothing of the second file is left: no asset, and no folder Party; Clients stays recycled.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val context = PipelineContext(testContext.repository, Some(testContext.user))
    val opened = testApp.service.imports.open(ImportSource.Upload, destFolderId = clients.id)
    val (first, firstFile) = asFileAt(opened, "Ceremony/vows.jpg")
    val (second, secondFile) = asFileAt(opened, "Party/dance.jpg")

    runPipeline((first, context))
    placeOf(assetOf(firstFile)) shouldBe List("Clients", "Ceremony")

    testApp.service.library.deleteFolderById(clients.persistedId)
    val result = dropped(runPipeline((second, context)))

    result.cause.get shouldBe an[ImportDestinationException]
    val failed = storedFile(secondFile)
    (failed.outcome, failed.reason, failed.assetId) shouldBe (
      ImportOutcome.Failed,
      Some(Const.Msg.Err.DESTINATION_IN_TRASH),
      None)
    val counted = testApp.service.imports.getById(opened.persistedId)
    (counted.imported, counted.failed) shouldBe (1, 1)

    rowCount("asset") shouldBe 1
    folderNames shouldBe Set("Clients", "Ceremony")
    folders.getById(clients.persistedId).isRecycled shouldBe true
  }

  test("A file below a folder name the folder rule refuses fails with that reason, and no folder of its path is created") {

    /**
     * Setup:
     *
     * An upload's Import with the folder Clients as its destination, of one image whose path is a folder Good and, under it, a
     * folder whose name is longer than a folder's name may be, run through the import pipeline.
     *
     * Assertions:
     *
     * The file is dropped at its completion and its Import file is failed, saying that a folder of the path cannot be created,
     * with the folder rule's own reason. No asset is left, and neither folder of the path exists, not even Good, whose name is
     * fine: the completion's transaction creates the folders and the asset together or not at all.
     */
    val clients = testApp.service.folder.add("Clients")
    val opened = testApp.service.imports.open(ImportSource.Upload, destFolderId = clients.id)
    val tooLong = "x" * (Api.Constraints.MAX_FOLDER_NAME_LENGTH + 1)
    val (photo, file) = asFileAt(opened, s"Good/$tooLong/a.jpg")

    val result = dropped(runPipeline((photo, PipelineContext(testContext.repository, Some(testContext.user)))))

    result.cause.get shouldBe an[ImportDestinationException]
    val failed = storedFile(file)
    failed.outcome shouldBe ImportOutcome.Failed
    failed.reason shouldBe Some(
      Const.Msg.Err.DESTINATION_FOLDER_NAME.format(Const.Msg.Err.VALUE_TOO_LONG.format(Api.Constraints.MAX_FOLDER_NAME_LENGTH)))
    rowCount("asset") shouldBe 0
    folderNames shouldBe Set("Clients")
  }

  test("A file below a folder that cannot be created because its name is taken fails with that reason, and is no duplicate") {

    /**
     * Setup:
     *
     * An upload's Import whose destination is the repository's root folder, of one image under a folder named as the root itself
     * is. No folder of that name is under the root to reuse (the root is its own parent, and is never its own child), and none
     * can be added there: the root holds the name among the folders whose parent it is.
     *
     * Assertions:
     *
     * The file is dropped at its completion with the folder as the cause, and its Import file is failed, saying that a folder of
     * the path cannot be created because its name is taken. It is not settled as a duplicate, which only a file whose content the
     * Library already has is: the Import counts one failed and no duplicate. No asset is left.
     */
    val opened =
      testApp.service.imports.open(ImportSource.Upload, destFolderId = Some(testContext.repository.rootFolderId))
    val (photo, file) = asFileAt(opened, s"${FieldConst.Folder.Name.ROOT}/a.jpg")

    val result = dropped(runPipeline((photo, PipelineContext(testContext.repository, Some(testContext.user)))))

    result.cause.get shouldBe an[ImportDestinationException]
    val failed = storedFile(file)
    (failed.outcome, failed.reason, failed.assetId) shouldBe (
      ImportOutcome.Failed,
      Some(Const.Msg.Err.DESTINATION_FOLDER_NAME.format(Const.Msg.Err.FOLDER_NAME_TAKEN)),
      None)
    val counted = testApp.service.imports.getById(opened.persistedId)
    (counted.failed, counted.duplicate) shouldBe (1, 0)
    rowCount("asset") shouldBe 0
  }

  /** `people/affleck.jpg` scaled to 640 by 893 on a white canvas `extraWidth` pixels wider, so frames of one face differ */
  private def portraitFrame(extraWidth: Int = 0): BufferedImage =
    val portrait = ImageIO.read(IntegrationTestUtil.getImportAsset("people/affleck.jpg").path.toFile)
    val canvas = new BufferedImage(640 + extraWidth, 893, BufferedImage.TYPE_3BYTE_BGR)
    val drawing = canvas.createGraphics()
    drawing.setColor(Color.WHITE)
    drawing.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
    drawing.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    drawing.drawImage(portrait, 0, 0, 640, 893, null)
    drawing.dispose()
    canvas

  /** A GIF of the frames, each shown for its delay in hundredths of a second; one frame makes a still GIF */
  private def gifBytes(frames: Seq[BufferedImage], delaysCs: Seq[Int]): Array[Byte] =
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    val out = new ByteArrayOutputStream()
    val stream = ImageIO.createImageOutputStream(out)
    writer.setOutput(stream)
    writer.prepareWriteSequence(null)

    frames.zip(delaysCs).foreach {
      case (frame, delayCs) =>
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null)
        val format = metadata.getNativeMetadataFormatName
        val root = metadata.getAsTree(format).asInstanceOf[IIOMetadataNode]
        val control = new IIOMetadataNode("GraphicControlExtension")
        control.setAttribute("disposalMethod", "none")
        control.setAttribute("userInputFlag", "FALSE")
        control.setAttribute("transparentColorFlag", "FALSE")
        control.setAttribute("delayTime", delayCs.toString)
        control.setAttribute("transparentColorIndex", "0")
        root.appendChild(control)
        metadata.setFromTree(format, root)
        writer.writeToSequence(new IIOImage(frame, null, metadata), null)
    }

    writer.endWriteSequence()
    stream.close()
    writer.dispose()
    out.toByteArray
}
