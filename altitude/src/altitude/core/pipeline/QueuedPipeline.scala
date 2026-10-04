package altitude.core.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.event.Logging
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.Supervision
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.RestartSource
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import altitude.core.QueueRefusedException

/**
 * A pipeline's flow run for the life of the app behind a queue that elements are offered to: the import and the purge queues. The
 * queue buffers `bufferSize` elements and admits `maxConcurrentOffers` offers at once, and backpressures beyond that. The stream
 * runs until [[shutdown]] completes the queue. A stream that fails stays down, unless `restartOnFailure` (development only,
 * `dev.restart_pipeline`) starts it again after a backoff, with a new queue. `describe` names an element in the log.
 */
class QueuedPipeline[In](
    name: String,
    flow: Flow[In, ?, NotUsed],
    describe: In => String,
    bufferSize: Int,
    maxConcurrentOffers: Int,
    shutdownTimeout: FiniteDuration,
    restartOnFailure: Boolean)(using system: ActorSystem[?]):
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

  // The queue of the stream's latest start, which offers go to; during a restart's backoff, the failed stream's, stopped
  private val currentQueue = AtomicReference[SourceQueueWithComplete[In]]()

  // Set by shutdown, after which a restart ends the stream instead of starting it again
  private val stopping = AtomicBoolean(false)

  /** One start of the stream: a new queue, the current one from here on, run through the flow */
  private def start(): Source[Any, NotUsed] =
    logger.info(s"Starting the $name queue")
    val (queue, source) = Source
      .queue[In](
        bufferSize = bufferSize,
        overflowStrategy = OverflowStrategy.backpressure,
        maxConcurrentOffers = maxConcurrentOffers)
      .preMaterialize()
    currentQueue.set(queue)
    source.via(flow).addAttributes(ActorAttributes.supervisionStrategy(resumeOnFailure))

  private val (killSwitch, done) = {
    // The first start is made here, so an offer never finds no queue; a restart makes each one after it
    val first = start()
    val stream =
      if !restartOnFailure then first
      else
        val starts = Iterator(first) ++ Iterator.continually(if stopping.get then Source.empty else start())
        RestartSource.onFailuresWithBackoff(QueuedPipeline.RESTART_SETTINGS)(() => starts.next())

    val (killSwitch, done) = stream.viaMat(KillSwitches.single)(Keep.right).toMat(Sink.ignore)(Keep.both).run()

    done.onComplete {
      case Success(_) => logger.info(s"The $name queue has finished")
      case Failure(e) => logger.error(s"The $name queue failed", e)
    }

    (killSwitch, done)
  }

  /**
   * Offers an element to the queue, waiting while the queue is full. The future fails with [[QueueRefusedException]] when the
   * queue does not take the element: it dropped it, it is closed, or its stream has stopped.
   */
  def offer(element: In): Future[Unit] =
    val offered =
      try currentQueue.get.offer(element)
      catch case NonFatal(e) => Future.failed(e)

    offered.transform {
      case Success(QueueOfferResult.Enqueued) => Success(())
      case Success(QueueOfferResult.Failure(cause)) => Failure(refused(element, cause.toString, cause))
      case Success(result) => Failure(refused(element, result.toString, null))
      case Failure(cause) => Failure(refused(element, cause.toString, cause))
    }

  private def refused(element: In, reason: String, cause: Throwable | Null): QueueRefusedException =
    val refusal = QueueRefusedException(s"The $name queue refused ${describe(element)}: $reason")
    refusal.initCause(cause)
    logger.warn(refusal.getMessage)
    refusal

  /**
   * Takes no more elements and waits, up to the timeout, for the stream to finish those it has accepted, so they are not cut off
   * by the database closing under them. What the timeout cuts off is left for the next startup.
   */
  def shutdown(): Unit =
    logger.info(s"Draining the $name queue, for at most $shutdownTimeout")
    stopping.set(true)

    // A queue that has stopped is a failed stream's, waiting out the backoff before a restart: nothing to drain or to wait for
    val queue = currentQueue.get
    if queue.watchCompletion().isCompleted then killSwitch.shutdown() else queue.complete()

    // A stream that failed is already over, and its failure was logged
    try Await.ready(done, shutdownTimeout)
    catch case _: TimeoutException => logger.warn(s"The $name queue did not finish within $shutdownTimeout; it is left as it is")

object QueuedPipeline:

  /** A failed stream restarts after a second, the wait doubling to 30 seconds while it keeps failing; each restart is an ERROR */
  private val RESTART_SETTINGS = RestartSettings(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.2)
    .withLogSettings(RestartSettings.LogSettings(Logging.ErrorLevel))
