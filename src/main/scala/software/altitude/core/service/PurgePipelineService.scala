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

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success

import software.altitude.core.Altitude
import software.altitude.core.AltitudeActorSystem
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.pipeline.flows._

class PurgePipelineService(app: Altitude) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  private val deleteAssetFilesFlow = DeleteAssetFilesFlow(app)
  private val deletePurgedFromDBFlow = DeletePurgedFromDBFlow(app)
  private val deletePersonFilesFlow = DeletePersonFilesFlow(app)

  private val combinedFlow: Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext]
      // Each repo has its own substream. We group by repo id and run the pipeline for each repo in parallel
      .groupBy(Int.MaxValue, _._2.repository.id)
      .via(deletePersonFilesFlow)
      .via(deleteAssetFilesFlow)
      .via(deletePurgedFromDBFlow)
      .mergeSubstreams
  private val queuePurgePipeline = runAsQueue()

  def run(
      source: Source[TAssetWithContext, NotUsed],
      outputSink: Sink[TAssetWithContext, Future[Seq[TAssetWithContext]]]): Future[Seq[TAssetWithContext]] = {

    source
      .via(combinedFlow)
      .runWith(outputSink)
  }

  private def runAsQueue() = {
    logger.info("Starting the purge queue pipeline")

    val (queue, source) = Source
      .queue[TAssetWithContext](
        bufferSize = parallelism * 2,
        overflowStrategy = OverflowStrategy.backpressure,
        maxConcurrentOffers = parallelism)
      .preMaterialize()

    val res = source
      .merge(Source.never) // Keep the queue open and never complete
      .via(combinedFlow)
      .toMat(Sink.foreach(_ => ()))(Keep.right)
      .run()

    res.onComplete {
      case Success(_) =>
        // this should never happen DURING the app run
        logger.error("Purge queue pipeline completed")
      case Failure(e) =>
        logger.error("Purge queue pipeline failed", e)
    }(ExecutionContext.global)

    queue
  }

  def addToQueue(asset: TAssetWithContext): Future[Unit] = {
    queuePurgePipeline
      .offer(asset)
      .map {
        case QueueOfferResult.Enqueued =>
          logger.info(s"Added asset to the purge queue: ${asset._1.fileName}")
        case QueueOfferResult.Dropped =>
          logger.warn(s"Asset dropped from the purge queue: ${asset._1.fileName}}")
        case QueueOfferResult.Failure(ex) =>
          logger.error(s"Failed to add asset to the purge queue: ${asset._1.fileName}", ex)
        case QueueOfferResult.QueueClosed =>
          logger.warn(s"Purge queue closed, asset dropped: ${asset._1.fileName}")
      }(ExecutionContext.global)
  }

  def shutdown(): Unit = {
    queuePurgePipeline.complete()
    Await.result(queuePurgePipeline.watchCompletion(), Duration.Inf)
  }
}
