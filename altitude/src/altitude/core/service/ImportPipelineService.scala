package altitude.core.service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import altitude.core.Altitude
import altitude.core.AltitudeActorSystem
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import altitude.core.pipeline.QueuedPipeline
import altitude.core.pipeline.flows.AddPreviewFlow
import altitude.core.pipeline.flows.AssignIdFlow
import altitude.core.pipeline.flows.CheckDuplicateFlow
import altitude.core.pipeline.flows.CheckMediaTypeFlow
import altitude.core.pipeline.flows.DetectFacesFlow
import altitude.core.pipeline.flows.DiscardDroppedFlow
import altitude.core.pipeline.flows.ExtractMetadataFlow
import altitude.core.pipeline.flows.FileStoreFlow
import altitude.core.pipeline.flows.IndexFlow
import altitude.core.pipeline.flows.MarkAsCompleteFlow
import altitude.core.pipeline.flows.MeasureImageFlow
import altitude.core.pipeline.flows.RecognizeFacesFlow
import altitude.core.pipeline.flows.SettleImportFileFlow
import altitude.core.pipeline.flows.StripBinaryDataFlow
import altitude.core.pipeline.sinks.AssetErrorLoggingSink

class ImportPipelineService(app: Altitude):
  val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  private val checkMediaTypeFlow = CheckMediaTypeFlow(app)
  private val assignIdFlow = AssignIdFlow(app)
  private val indexFlow = IndexFlow(app)
  private val detectFacesFlow = DetectFacesFlow(app)
  private val recognizeFacesFlow = RecognizeFacesFlow(app)
  private val measureImageFlow = MeasureImageFlow(app)
  private val extractMetadataFlow = ExtractMetadataFlow(app)
  private val fileStoreFlow = FileStoreFlow(app)
  private val addPreviewFlow = AddPreviewFlow(app)
  private val checkDuplicateFlow = CheckDuplicateFlow(app)
  private val markAsCompleteFlow = MarkAsCompleteFlow(app)
  private val discardDroppedFlow = DiscardDroppedFlow(app)
  private val settleImportFileFlow = SettleImportFileFlow(app)
  private val stripBinaryDataFlow = StripBinaryDataFlow()
  private val errorLoggingSink = AssetErrorLoggingSink()

  /**
   * One pipeline for both engines. Stage work runs on the import dispatcher, whose `importParallelism` threads bound every import
   * at once; the stream only routes. The stages that only read and decode the file (metadata, face detection, image measurement,
   * the preview) take up to `importParallelism` assets of a repository at once, every other stage one, and all keep upload order,
   * so faces are matched one asset at a time in upload order. Each stage commits its own short transaction before the next reads
   * the asset. An Import's file rides along (`AssetWithData.origin`): the completion settles an imported one in its transaction,
   * the last stage a dropped one after the discard.
   */
  private val combinedFlow: Flow[TDataAssetWithContext, TAssetOrInvalidWithContext, NotUsed] = Flow[TDataAssetWithContext]
    // One substream per repository, run in parallel
    .groupBy(Int.MaxValue, _._2.repository.id)
    .via(checkMediaTypeFlow)
    .via(checkDuplicateFlow)
    .via(assignIdFlow)
    .via(extractMetadataFlow)
    .via(indexFlow)
    .via(detectFacesFlow)
    .via(measureImageFlow)
    .via(recognizeFacesFlow)
    .via(fileStoreFlow)
    .via(addPreviewFlow)
    .via(markAsCompleteFlow)
    .via(discardDroppedFlow)
    .via(settleImportFileFlow)
    .via(stripBinaryDataFlow)
    .mergeSubstreams
    .alsoTo(errorLoggingSink)

  private val queue = QueuedPipeline[TDataAssetWithContext](
    "import",
    combinedFlow,
    describe = _._1.asset.fileName,
    bufferSize = app.parallelism * 2,
    shutdownTimeout = () => app.settings.current.pipelineShutdownTimeout.seconds,
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
