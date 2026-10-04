package altitude.core.service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.AltitudeActorSystem
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import altitude.core.pipeline.QueuedPipeline
import altitude.core.pipeline.flows.AddPreviewFlow
import altitude.core.pipeline.flows.AssignIdFlow
import altitude.core.pipeline.flows.CheckDuplicateFlow
import altitude.core.pipeline.flows.CheckMediaTypeFlow
import altitude.core.pipeline.flows.DiscardDroppedFlow
import altitude.core.pipeline.flows.ExtractMetadataFlow
import altitude.core.pipeline.flows.FacialRecognitionFlow
import altitude.core.pipeline.flows.FileStoreFlow
import altitude.core.pipeline.flows.IndexFlow
import altitude.core.pipeline.flows.MarkAsCompleteFlow
import altitude.core.pipeline.flows.StripBinaryDataFlow
import altitude.core.pipeline.sinks.AssetErrorLoggingSink
import altitude.core.pipeline.sinks.WsAssetProcessedNotificationSink

class ImportPipelineService(app: Altitude):
  val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  private val checkMediaTypeFlow = CheckMediaTypeFlow(app)
  private val assignIdFlow = AssignIdFlow(app)
  private val indexFlow = IndexFlow(app)
  private val facialRecognitionFlow = FacialRecognitionFlow(app)
  private val extractMetadataFlow = ExtractMetadataFlow(app)
  private val fileStoreFlow = FileStoreFlow(app)
  private val addPreviewFlow = AddPreviewFlow(app)
  private val checkDuplicateFlow = CheckDuplicateFlow(app)
  private val stripBinaryDataFlow = StripBinaryDataFlow()
  private val markAsCompleteFlow = MarkAsCompleteFlow(app)
  private val discardDroppedFlow = DiscardDroppedFlow(app)
  private val wsNotificationSink = WsAssetProcessedNotificationSink(app)
  private val errorLoggingSink = AssetErrorLoggingSink()

  /**
   * One pipeline for both engines. Every stage does its work before it hands the asset on, so a stage holds one asset at a time;
   * the asynchronous boundaries let up to four of them work on different assets at once. Each stage's writes are a short
   * transaction of its own (faces are detected before theirs opens), which SQLite's single write connection runs one after
   * another, and each stage commits before the next one reads the asset.
   */
  private val combinedFlow: Flow[TDataAssetWithContext, TAssetOrInvalidWithContext, NotUsed] = Flow[TDataAssetWithContext]
    // Each repo has its own substream. We group by repo id and run the pipeline for each repo in parallel
    .groupBy(Int.MaxValue, _._2.repository.id)
    .via(checkMediaTypeFlow)
    .via(checkDuplicateFlow)
    .via(assignIdFlow)
    .via(extractMetadataFlow)
    .via(indexFlow)
    .async
    .via(facialRecognitionFlow)
    .async
    .via(fileStoreFlow)
    .async
    .via(addPreviewFlow)
    .via(stripBinaryDataFlow)
    .via(markAsCompleteFlow)
    .via(discardDroppedFlow)
    .mergeSubstreams
    .alsoTo(wsNotificationSink)
    .alsoTo(errorLoggingSink)

  private val queue = QueuedPipeline[TDataAssetWithContext](
    "import",
    combinedFlow,
    bufferSize = app.parallelism * 2,
    maxConcurrentOffers = app.parallelism)

  def run(
      source: Source[TDataAssetWithContext, NotUsed],
      outputSink: Sink[TAssetOrInvalidWithContext, Future[Seq[TAssetOrInvalidWithContext]]])
      : Future[Seq[TAssetOrInvalidWithContext]] =
    source
      .via(combinedFlow)
      .runWith(outputSink)

  def addToQueue(asset: TDataAssetWithContext): Future[Unit] =
    queue
      .offer(asset)
      .map {
        case QueueOfferResult.Enqueued =>
          logger.debug(s"Added asset to the import queue: ${asset._1.asset.fileName}")
        case QueueOfferResult.Dropped =>
          logger.warn(s"Asset dropped from the import queue: ${asset._1.asset.fileName}")
        case QueueOfferResult.Failure(ex) =>
          logger.error(s"Failed to add asset to the import queue: ${asset._1.asset.fileName}", ex)
        case QueueOfferResult.QueueClosed =>
          logger.warn(s"Import queue closed, asset dropped: ${asset._1.asset.fileName}")
      }(system.executionContext)

  def shutdown(): Unit = queue.shutdown()
