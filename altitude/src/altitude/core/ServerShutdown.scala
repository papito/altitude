package altitude.core

import io.undertow.server.handlers.GracefulShutdownHandler
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/**
 * The server's stop, as Pekko coordinated-shutdown tasks. Pekko runs its coordinated shutdown from a JVM shutdown hook, so a
 * SIGTERM, a Ctrl-C and a logoff all stop the server this way:
 *
 *   - `service-unbind`: new requests are answered 503.
 *   - `service-requests-done`: the requests under way get [[REQUESTS_GRACE]] to finish.
 *   - `service-stop`: `Altitude.cleanup()` drains the queues and closes the database, then the HTTP server stops.
 *
 * The actor system, which the queues run on, terminates in a later phase. `App.main` builds the HTTP server itself rather than
 * through cask's `main`, whose own shutdown hook would stop the server at once, beside these tasks and not after them.
 *
 * The last task is registered first, as soon as there is an instance ([[registerCleanup]]), and the two of the requests once
 * there is a server ([[registerRequests]]): a stop during the startup tasks cleans up too.
 */
object ServerShutdown:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** How long the requests under way at the stop get to finish */
  val REQUESTS_GRACE: FiniteDuration = 10.seconds

  /** The `service-requests-done` phase's timeout: the grace and a margin. Pekko's default of 5 seconds would cut the grace. */
  val REQUESTS_PHASE_TIMEOUT: FiniteDuration = 15.seconds

  /**
   * The `service-stop` phase's timeout, a backstop alone: `Altitude.cleanup()` bounds itself by the Pipeline shutdown timeout
   * Setting, and past this timeout Pekko would terminate the actor system under the queues still draining.
   */
  val STOP_PHASE_TIMEOUT: FiniteDuration = 10.minutes

  /**
   * Undertow's `SHUTDOWN_TIMEOUT`: how long its stop waits for a request still held before interrupting it, so none hangs the
   * stop
   */
  val SERVER_STOP_TIMEOUT: FiniteDuration = 5.seconds

  /**
   * Adds the stop's last task to the actor system's coordinated shutdown: the cleanup, then the HTTP server's stop. `App`
   * registers it the moment its instance is built, before the startup tasks and before there is a server, so a SIGTERM at any
   * moment from then on drains the queues and closes the database. Without it Pekko terminates the actor system under the queues.
   *
   * @param cleanup
   *   `Altitude.cleanup()`
   * @param stopServer
   *   stops the HTTP server and answers whether there was one to stop; run after `cleanup`, and also when `cleanup` fails
   */
  def registerCleanup(system: ActorSystem[?], cleanup: () => Unit, stopServer: () => Boolean): Unit =
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceStop, "cleanup-then-stop-server") {
      () =>
        onOwnThread("altitude-stop") {
          logger.info("Draining the queues and closing the database")
          // A failed cleanup must still stop the server, whatever it failed with: an error (a `LinkageError`, a
          // `StackOverflowError`) as much as an exception. Hence `Throwable`, and the `finally` for a log call that fails too.
          try cleanup()
          catch case e: Throwable => logger.error("The cleanup failed; the HTTP server is stopped all the same", e)
          finally
            if stopServer() then logger.info("The HTTP server has stopped")
            else logger.info("The stop came before the HTTP server was started: there is none to stop")
        }
    }

  /**
   * Adds the two tasks of the requests, once there is a server to take any.
   *
   * @param requests
   *   the handler in front of every route, which refuses new requests and knows of those under way
   */
  def registerRequests(
      system: ActorSystem[?],
      requests: GracefulShutdownHandler,
      requestsGrace: FiniteDuration = REQUESTS_GRACE): Unit =
    val shutdown = CoordinatedShutdown(system)

    shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "refuse-new-requests") {
      () =>
        logger.info("Stopping: new requests are answered 503")
        requests.shutdown()
        Future.successful(Done)
    }

    shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "await-requests-under-way") {
      () =>
        onOwnThread("altitude-stop-requests") {
          if requests.awaitShutdown(requestsGrace.toMillis) then logger.info("The requests under way have finished")
          else logger.warn(s"Requests are still under way after $requestsGrace: stopping without waiting for them")
        }
    }

  /** Whether the actor system's coordinated shutdown has begun: a start still under way then has nothing left to serve */
  def hasBegun(system: ActorSystem[?]): Boolean = CoordinatedShutdown(system).shutdownReason().isDefined

  /**
   * Runs blocking work on a thread of its own and completes when it ends: a task's function runs on Pekko's internal dispatcher,
   * which a drain of minutes must not hold a thread of. A daemon thread, so a stop that hangs can never keep the JVM up.
   *
   * The future completes whatever the work ends with, an error included: left incomplete, the task's phase would wait out its
   * whole timeout (ten minutes for the cleanup) for a thread that is gone. Pekko logs a failed task and goes on to the next
   * phase.
   */
  private def onOwnThread(name: String)(work: => Unit): Future[Done] =
    val done = Promise[Done]()
    val run: Runnable = () =>
      try
        work
        done.success(Done): Unit
      catch case e: Throwable => done.failure(e): Unit
    val thread = Thread(run, name)
    thread.setDaemon(true)
    thread.start()
    done.future
