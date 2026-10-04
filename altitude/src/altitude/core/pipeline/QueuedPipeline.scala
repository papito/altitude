package altitude.core.pipeline

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.Supervision
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

/**
 * A pipeline's flow run once, for the life of the app, behind a queue that elements are offered to: the import and the purge
 * queues. The queue buffers `bufferSize` elements and admits `maxConcurrentOffers` offers at once, and backpressures beyond that.
 * The stream runs until [[shutdown]] completes the queue.
 */
class QueuedPipeline[In](
    name: String,
    flow: Flow[In, ?, NotUsed],
    bufferSize: Int,
    maxConcurrentOffers: Int,
    shutdownTimeout: FiniteDuration)(using system: ActorSystem[?]):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private given ExecutionContext = system.executionContext

  /**
   * The last resort for a failure outside a stage's work, which `PipelineUtils.guarded` does not see: the element is dropped and
   * the queue goes on. It reaches no sink, so it is neither reported nor discarded; the stages never rely on this.
   */
  private val resumeOnFailure: Supervision.Decider = {
    ex =>
      logger.error(s"An element failed outside a stage of the $name queue and was dropped", ex)
      Supervision.Resume
  }

  private val (queue, done) = {
    logger.debug(s"Starting the $name queue")

    val (queue, source) = Source
      .queue[In](
        bufferSize = bufferSize,
        overflowStrategy = OverflowStrategy.backpressure,
        maxConcurrentOffers = maxConcurrentOffers)
      .preMaterialize()

    val done = source
      .via(flow)
      .toMat(Sink.ignore)(Keep.right)
      .withAttributes(ActorAttributes.supervisionStrategy(resumeOnFailure))
      .run()

    done.onComplete {
      case Success(_) => logger.info(s"The $name queue has finished")
      case Failure(e) => logger.error(s"The $name queue failed", e)
    }

    (queue, done)
  }

  def offer(element: In): Future[QueueOfferResult] = queue.offer(element)

  /**
   * Takes no more elements and waits, up to the timeout, for the stream to finish those it has accepted, so they are not cut off
   * by the database closing under them. What the timeout cuts off is left for the next startup.
   */
  def shutdown(): Unit =
    logger.info(s"Draining the $name queue, for at most $shutdownTimeout")
    queue.complete()

    // A stream that failed is already over, and its failure was logged
    try Await.ready(done, shutdownTimeout)
    catch case _: TimeoutException => logger.warn(s"The $name queue did not finish within $shutdownTimeout; it is left as it is")
