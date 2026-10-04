package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
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
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.ImageException
import altitude.core.NotFoundException
import altitude.core.QueueRefusedException
import altitude.core.RequestContext
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.models.Person
import altitude.core.models.Repository
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

  /** Replaces the repository's `files` directory with a plain file, so storing any asset of the repository fails */
  private def breakFileStore(repository: Repository): Unit =
    val files = Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, repository.persistedId, Const.DataStore.FILES)
    FileUtils.deleteDirectory(files.toFile)
    Files.createDirectories(files.getParent)
    Files.createFile(files)

  /** Puts the repository's `files` directory back after [[breakFileStore]] */
  private def restoreFileStore(repository: Repository): Unit =
    Files.delete(Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, repository.persistedId, Const.DataStore.FILES))

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
      shutdownTimeout = 30.seconds,
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
      PipelineContext(testContext.repository, testContext.user))

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
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)

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
      Await.result(pipeline.addToQueue((upload, PipelineContext(testContext.repository, testContext.user))), 30.seconds)
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

    val element: TDataAssetOrInvalidWithContext = (Left(dataAsset), PipelineContext(otherRepository, testContext.user))
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
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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

    val unstorable = testContext.makeAssetWithData(Some(testContext.makeAsset(repository = Some(brokenRepository))))
    val photo = testContext.makeAssetWithData(Some(testContext.makeAsset(repository = Some(repository))))

    val pipelineRes = runPipeline(
      (unstorable, PipelineContext(brokenRepository, testContext.user)),
      (photo, PipelineContext(repository, testContext.user)))
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
     * The pipeline completes with the clip dropped with `VideoException` from the preview stage; its staged file, its row and its
     * stored file are deleted, and it has no preview. The photo behind it is imported.
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
    val pipelineContext = PipelineContext(testContext.repository, testContext.user)
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
}
