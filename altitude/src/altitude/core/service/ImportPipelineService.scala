package altitude.core.service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.jdk.DurationConverters.*
import scala.util.Failure
import scala.util.Success

import altitude.core.Altitude
import altitude.core.AltitudeActorSystem
import altitude.core.Const
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
    describe = _._1.asset.fileName,
    bufferSize = app.parallelism * 2,
    maxConcurrentOffers = app.parallelism,
    shutdownTimeout = app.config.getDuration(Const.Conf.PIPELINE_SHUTDOWN_TIMEOUT).toScala,
    restartOnFailure = app.isPipelineRestartEnabled
  )

  def run(
      source: Source[TDataAssetWithContext, NotUsed],
      outputSink: Sink[TAssetOrInvalidWithContext, Future[Seq[TAssetOrInvalidWithContext]]])
      : Future[Seq[TAssetOrInvalidWithContext]] =
    source
      .via(combinedFlow)
      .runWith(outputSink)

  /**
   * Offers a staged upload to the import queue. One the queue refuses has its staged file deleted, since nothing else would
   * before the next startup, and fails with `QueueRefusedException`.
   */
  def addToQueue(asset: TDataAssetWithContext): Future[Unit] =
    val (dataAsset, _) = asset
    queue
      .offer(asset)
      .andThen {
        case Success(_) => logger.debug(s"Added asset to the import queue: ${dataAsset.asset.fileName}")
        case Failure(_) => app.service.staging.discard(dataAsset.path)
      }(system.executionContext)

  def shutdown(): Unit = queue.shutdown()
