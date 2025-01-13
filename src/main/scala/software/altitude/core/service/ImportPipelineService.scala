package software.altitude.core.service
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.AltitudeActorSystem
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import software.altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import software.altitude.core.pipeline.flows.AddPreviewFlow
import software.altitude.core.pipeline.flows.AssignIdFlow
import software.altitude.core.pipeline.flows.CheckDuplicateFlow
import software.altitude.core.pipeline.flows.CheckMediaTypeFlow
import software.altitude.core.pipeline.flows.ExtractMetadataFlow
import software.altitude.core.pipeline.flows.FacialRecognitionFlow
import software.altitude.core.pipeline.flows.FileStoreFlow
import software.altitude.core.pipeline.flows.MarkAsCompleteFlow
import software.altitude.core.pipeline.flows.PersistAndIndexAssetFlow
import software.altitude.core.pipeline.flows.StripBinaryDataFlow
import software.altitude.core.pipeline.sinks.AssetErrorLoggingSink
import software.altitude.core.pipeline.sinks.WsAssetProcessedNotificationSink

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success

class ImportPipelineService(app: Altitude) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  private val checkMediaTypeFlow = CheckMediaTypeFlow(app)
  private val assignIdFlow = AssignIdFlow(app)
  private val persistAndIndexFlow = PersistAndIndexAssetFlow(app)
  private val facialRecognitionFlow = FacialRecognitionFlow(app)
  private val extractMetadataFlow = ExtractMetadataFlow(app)
  private val fileStoreFlow = FileStoreFlow(app)
  private val addPreviewFlow = AddPreviewFlow(app)
  private val checkDuplicateFlow = CheckDuplicateFlow(app)
  private val stripBinaryDataFlow = StripBinaryDataFlow(app)
  private val markAsCompleteFlow = MarkAsCompleteFlow(app)
  private val wsNotificationSink = WsAssetProcessedNotificationSink(app)
  private val errorLoggingSink = AssetErrorLoggingSink()

  private val combinedFlow: Flow[TDataAssetWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetWithContext]
      // Each repo has its own substream. We group by repo id and run the pipeline for each repo in parallel
      .groupBy(Int.MaxValue, _._2.repository.id)
      .via(checkMediaTypeFlow)
      .via(checkDuplicateFlow)
      .via(assignIdFlow)
      .via(extractMetadataFlow)
      .via(persistAndIndexFlow)
      .async
      .via(facialRecognitionFlow)
      .async
      .via(fileStoreFlow)
      .async
      .via(addPreviewFlow)
      .via(stripBinaryDataFlow)
      .via(markAsCompleteFlow)
      .mergeSubstreams
      .alsoTo(wsNotificationSink)
      .alsoTo(errorLoggingSink)
  private val queueImportPipeline = runAsQueue()

  def run(
      source: Source[TDataAssetWithContext, NotUsed],
      outputSink: Sink[TAssetOrInvalidWithContext, Future[Seq[TAssetOrInvalidWithContext]]])
      : Future[Seq[TAssetOrInvalidWithContext]] = {

    source
      .via(combinedFlow)
      .runWith(outputSink)
  }

  private def runAsQueue() = {
    logger.info("Starting the import queue pipeline")

    val (queue, source) = Source
      .queue[TDataAssetWithContext](parallelism * 2, OverflowStrategy.backpressure, maxConcurrentOffers = parallelism)
      .preMaterialize()

    val res = source
      .merge(Source.never) // Keep the queue open and never complete
      .via(combinedFlow)
      .toMat(Sink.foreach(_ => ()))(Keep.right)
      .run()

    res.onComplete {
      case Success(_) =>
        // this should never happen DURING the app run
        logger.error("Import queue pipeline completed")
      case Failure(e) =>
        logger.error("Import queue pipeline failed", e)
    }(ExecutionContext.global)

    queue
  }

  def addToQueue(asset: TDataAssetWithContext): Future[Unit] = {
    queueImportPipeline
      .offer(asset)
      .map {
        case QueueOfferResult.Enqueued =>
          logger.info(s"Added asset to the import queue: ${asset._1.asset.fileName}")
        case QueueOfferResult.Dropped =>
          logger.warn(s"Asset dropped from the import queue: ${asset._1.asset.fileName}}")
        case QueueOfferResult.Failure(ex) =>
          logger.error(s"Failed to add asset to the import queue: ${asset._1.asset.fileName}", ex)
        case QueueOfferResult.QueueClosed =>
          logger.warn(s"Import queue closed, asset dropped: ${asset._1.asset.fileName}")
      }(ExecutionContext.global)
  }

  def shutdown(): Unit = {
    queueImportPipeline.complete()
    Await.result(queueImportPipeline.watchCompletion(), Duration.Inf)
  }
}
