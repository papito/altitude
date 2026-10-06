package altitude.core.service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.DurationConverters._

import altitude.core.Altitude
import altitude.core.AltitudeActorSystem
import altitude.core.Const
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.QueuedPipeline
import altitude.core.pipeline.flows._

class PurgePipelineService(app: Altitude):
  val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  private val deletePersonFilesFlow = DeletePersonFilesFlow(app)
  private val deleteAssetFilesFlow = DeleteAssetFilesFlow(app)
  private val deletePurgedFromDBFlow = DeletePurgedFromDBFlow(app)

  private val combinedFlow: Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext]
      // Each repo has its own substream. We group by repo id and run the pipeline for each repo in parallel
      .groupBy(Int.MaxValue, _._2.repository.id)
      .via(deletePersonFilesFlow)
      .via(deleteAssetFilesFlow)
      .via(deletePurgedFromDBFlow)
      .mergeSubstreams

  private val queue = QueuedPipeline[TAssetWithContext](
    "purge",
    combinedFlow,
    describe = asset => s"asset [${asset._1.persistedId}]",
    bufferSize = app.parallelism * 2,
    maxConcurrentOffers = app.parallelism,
    shutdownTimeout = app.config.getDuration(Const.Conf.PIPELINE_SHUTDOWN_TIMEOUT).toScala,
    restartOnFailure = app.isPipelineRestartEnabled
  )

  def run(
      source: Source[TAssetWithContext, NotUsed],
      outputSink: Sink[TAssetWithContext, Future[Seq[TAssetWithContext]]]): Future[Seq[TAssetWithContext]] =
    source
      .via(combinedFlow)
      .runWith(outputSink)

  /**
   * Queues the assets for purging one at a time, offering each once the queue has taken the one before, so a recycle bin of any
   * size is queued under the queue's backpressure while the caller goes on. An asset the queue does not take stays marked for
   * purging, for the startup job to queue again.
   */
  def enqueue(assets: Seq[TAssetWithContext]): Unit =
    given ExecutionContext = system.executionContext

    Source(assets)
      .mapAsync(1) {
        asset =>
          val assetId = asset._1.persistedId
          queue
            .offer(asset)
            .map(_ => logger.trace(s"Asset [$assetId] queued for purging"))
            .recover { case ex => logger.error(s"Asset [$assetId] not queued for purging", ex) }
      }
      .run()

  def shutdown(): Unit = queue.shutdown()
