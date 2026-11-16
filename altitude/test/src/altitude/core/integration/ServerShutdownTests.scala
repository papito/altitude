package altitude.core.integration

import altitude.test.TestAltitudeApp
import altitude.test.TestFocus
import com.typesafe.config.ConfigFactory
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.BlockingHandler
import io.undertow.server.handlers.GracefulShutdownHandler
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

import altitude.core.Altitude
import altitude.core.ServerShutdown

/**
 * The server's stop (`ServerShutdown`): the coordinated shutdown of a second instance against the test database, behind an HTTP
 * server built as `App.main` builds its own. Nothing else may run against that instance meanwhile.
 */
@DoNotDiscover class ServerShutdownTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with Eventually
  with TestFocus {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(20, Seconds)), interval = scaled(Span(50, Millis)))

  /**
   * A second instance and its HTTP server on a free port, with the stop registered. `/held` answers only once the test releases
   * it; every other path answers at once. The fixture records what the stop's last task does, in order.
   */
  final private class Stoppable(requestsGrace: FiniteDuration) {
    val instance: Altitude = TestAltitudeApp.build(testApp.dataSourceType)

    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** What the last task did, in order: the cleanup, with what the server answered a new request while it ran, then the stop */
    val events = ConcurrentLinkedQueue[String]()

    private val routes: HttpHandler = BlockingHandler {
      exchange =>
        if (exchange.getRequestPath == "/held") {
          entered.countDown()
          released.await()
        }
        exchange.getResponseSender.send("answered")
    }

    val requestsHandler = GracefulShutdownHandler(routes)

    private val server = Undertow.builder
      .addHttpListener(0, "localhost")
      .setServerOption[Integer](UndertowOptions.SHUTDOWN_TIMEOUT, ServerShutdown.SERVER_STOP_TIMEOUT.toMillis.toInt)
      .setHandler(requestsHandler)
      .build
    server.start()

    val host: String =
      s"http://localhost:${server.getListenerInfo.get(0).getAddress.asInstanceOf[InetSocketAddress].getPort}"

    ServerShutdown.register(
      instance.actorSystem,
      requestsHandler,
      cleanup = () => {
        events.add(s"cleanup, the server answering ${statusOf("/new")}")
        instance.cleanup()
      },
      stopServer = () => {
        events.add("server stop")
        server.stop()
      },
      requestsGrace
    )

    def statusOf(path: String): Int = requests.get(s"$host$path", check = false).statusCode

    /** Whether the instance still reads its database, which its cleanup closes */
    def readsDatabase: Boolean = Try(instance.service.system.readMetadata).isSuccess

    /** A request the server holds until [[release]]; the future is the status it is answered with, once this returns */
    def holdRequest(): Future[Int] = {
      val answer =
        Future(requests.get(s"$host/held", check = false, readTimeout = 120000).statusCode)(using ExecutionContext.global)
      entered.await(20, TimeUnit.SECONDS) shouldBe true
      answer
    }

    def release(): Unit = released.countDown()

    /** Starts the coordinated shutdown, as the JVM's shutdown hook does at a SIGTERM */
    def stop(): Future[Done] = CoordinatedShutdown(instance.actorSystem).run(CoordinatedShutdown.UnknownReason)

    /**
     * Leaves nothing behind when a test fails before its stop has run: terminating the actor system runs the coordinated
     * shutdown, and so the cleanup and the server's stop. After a stop that has run, there is nothing left to terminate.
     */
    def discard(): Unit = {
      release()
      instance.actorSystem.terminate()
      Await.ready(instance.actorSystem.whenTerminated, 3.minutes)
    }
  }

  private def withStoppable[T](requestsGrace: FiniteDuration)(f: Stoppable => T): T = {
    val stoppable = new Stoppable(requestsGrace)
    try f(stoppable)
    finally stoppable.discard()
  }

  test("A stop refuses new requests, lets the one under way finish, then cleans the instance up and stops the server") {

    /**
     * Setup:
     *
     * A second instance behind an HTTP server with the stop's three tasks registered, a request under way that the server holds,
     * and then the coordinated shutdown, started as a SIGTERM starts it.
     *
     * Assertions:
     *
     * While the request is under way a new request is answered 503, the instance still reads its database and the last task has
     * not begun. Once the request is released it is answered 200, and only then does the last task run: the cleanup first, with
     * the server still up and refusing, then the server's stop. Afterwards the database is closed, the server takes no
     * connection, and the actor system has terminated.
     *
     * Edge cases:
     *
     * Before the stop the server answers a new request 200 beside the held one.
     */
    withStoppable(requestsGrace = 2.minutes) {
      stoppable =>
        val held = stoppable.holdRequest()
        stoppable.statusOf("/new") shouldBe 200
        stoppable.readsDatabase shouldBe true

        val stopped = stoppable.stop()

        eventually(stoppable.statusOf("/new") shouldBe 503)
        // The wait for the request under way is the next phase: given time, a stop that did not wait would have cleaned up
        Thread.sleep(1000)
        stoppable.events.asScala.toList shouldBe Nil
        stoppable.readsDatabase shouldBe true
        held.isCompleted shouldBe false
        stopped.isCompleted shouldBe false

        stoppable.release()
        Await.result(held, 30.seconds) shouldBe 200

        Await.ready(stopped, 2.minutes)
        stoppable.events.asScala.toList shouldBe List("cleanup, the server answering 503", "server stop")
        stoppable.readsDatabase shouldBe false
        an[Exception] should be thrownBy stoppable.statusOf("/new")
        Await.ready(stoppable.instance.actorSystem.whenTerminated, 30.seconds)
    }
  }

  test("A request still held after its grace does not hang the stop") {

    /**
     * Setup:
     *
     * A second instance behind an HTTP server, with a grace of half a second for the requests under way, and a request the server
     * never releases. Then the coordinated shutdown.
     *
     * Assertions:
     *
     * The stop goes on once the grace is over: the cleanup runs with the request still held, then the server stops, cutting the
     * request off after its own stop timeout instead of waiting for it. The stop completes, and the held request ends without a
     * 200.
     */
    withStoppable(requestsGrace = 500.millis) {
      stoppable =>
        val held = stoppable.holdRequest()

        val started = System.nanoTime
        Await.ready(stoppable.stop(), 2.minutes)
        val took = FiniteDuration(System.nanoTime - started, TimeUnit.NANOSECONDS)

        stoppable.events.asScala.toList shouldBe List("cleanup, the server answering 503", "server stop")
        stoppable.readsDatabase shouldBe false
        // The grace, and then the server's own stop timeout, were each waited out
        took should be >= (500.millis + ServerShutdown.SERVER_STOP_TIMEOUT)

        Try(Await.result(held, 30.seconds)).toOption should not be Some(200)
        Await.ready(stoppable.instance.actorSystem.whenTerminated, 30.seconds)
    }
  }

  test("A cleanup that dies of an error still stops the server, and the stop does not wait its phase out") {

    /**
     * Setup:
     *
     * An actor system of the test's own, its cleanup phase given 30 seconds, with the stop's three tasks registered over a
     * handler that serves nothing. The cleanup throws a `LinkageError`, which is no ordinary exception: `NonFatal` does not match
     * it, and `Try` lets it through. Then the coordinated shutdown.
     *
     * Assertions:
     *
     * The server's stop runs after the failed cleanup, and the stop as a whole completes at once, the actor system terminated,
     * instead of waiting the cleanup phase's 30 seconds for a task whose thread is gone.
     *
     * Edge cases:
     *
     * A server's stop that dies of an error itself ends its task too: the stop completes as promptly.
     */
    def stopOf(cleanup: () => Unit, stopServer: () => Unit): FiniteDuration = {
      val phases = ConfigFactory.parseString("pekko.coordinated-shutdown.phases.service-stop.timeout = 30 s")
      val system = ActorSystem(Behaviors.empty[Nothing], "server-shutdown-tests", phases.withFallback(ConfigFactory.load()))
      try {
        ServerShutdown.register(system, GracefulShutdownHandler(_ => ()), cleanup, stopServer, requestsGrace = 1.second)
        val started = System.nanoTime
        Await.ready(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 2.minutes)
        FiniteDuration(System.nanoTime - started, TimeUnit.NANOSECONDS)
      } finally {
        system.terminate()
        Await.ready(system.whenTerminated, 1.minute)
      }
    }

    val events = ConcurrentLinkedQueue[String]()
    val afterFailedCleanup = stopOf(
      cleanup = () => {
        events.add("cleanup")
        throw new LinkageError("The cleanup dies of an error")
      },
      stopServer = () => events.add("server stop"): Unit
    )
    events.asScala.toList shouldBe List("cleanup", "server stop")
    afterFailedCleanup should be < 15.seconds

    val afterFailedServerStop = stopOf(cleanup = () => (), stopServer = () => throw new LinkageError("The server's stop dies"))
    afterFailedServerStop should be < 15.seconds
  }

  test("The stop's phases are given the time their tasks need") {

    /**
     * Setup:
     *
     * The suite's instance, whose actor system every instance builds the same way.
     *
     * Assertions:
     *
     * The phase that waits for the requests under way lasts longer than their grace, and the phase that cleans up is a backstop
     * of ten minutes, far above the three drains of the default Pipeline shutdown timeout. Without them Pekko's five-second
     * default would cut both short.
     */
    val phases = testApp.actorSystem.settings.config.getConfig("pekko.coordinated-shutdown.phases")

    phases.getDuration("service-requests-done.timeout").toMillis shouldBe ServerShutdown.REQUESTS_PHASE_TIMEOUT.toMillis
    phases.getDuration("service-stop.timeout").toMillis shouldBe 10.minutes.toMillis

    ServerShutdown.REQUESTS_GRACE shouldBe 10.seconds
    ServerShutdown.REQUESTS_PHASE_TIMEOUT should be > ServerShutdown.REQUESTS_GRACE
    ServerShutdown.SERVER_STOP_TIMEOUT shouldBe 5.seconds
    val drains = 3 * testApp.settings.current.pipelineShutdownTimeout
    ServerShutdown.STOP_PHASE_TIMEOUT.toSeconds should be > (drains + ServerShutdown.SERVER_STOP_TIMEOUT.toSeconds)
  }
}
