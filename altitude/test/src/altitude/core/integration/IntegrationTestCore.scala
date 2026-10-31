package altitude.core.integration

import altitude.test.{ IntegrationTestUtil, TestContext, TestFocus }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.*
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.time.{ Millis, Seconds, Span }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

import altitude.core.*
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.*

abstract class IntegrationTestCore
  extends funsuite.AnyFunSuite
  with AltitudeTestApp
  with BeforeAndAfter
  with BeforeAndAfterEach
  with OptionValues
  with TestFocus {
  final protected val log: Logger = LoggerFactory.getLogger(getClass)

  var testContext: TestContext = new TestContext(testApp)

  implicit val scheduler: Scheduler = testApp.actorSystem.scheduler
  implicit val timeout: Timeout = 3.seconds

  def query(sql: String, values: Any*): List[Map[String, AnyRef]] = {
    val res =
      new QueryRunner()
        .query(RequestContext.getConn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object])*)
        .asScala
        .toList

    res.map(_.asScala.toMap[String, AnyRef])
  }

  def update(sql: String, values: Any*): Unit = {
    new QueryRunner().update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
  }

  /** A timestamp in the engine's storage form; SQLite's is the canonical text every writer must produce */
  def getSqlDateTime(t: java.sql.Timestamp): Any = {
    testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES => t
      case Const.DbEngineName.SQLITE => t.toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)
      case _ => throw new IllegalArgumentException("Unsupported data source type")
    }
  }

  override def beforeEach(): Unit = {
    testApp.clearState()
    testApp.isInitialized = false
    testContext = new TestContext(testApp)

    // Every integration test has at least one repository and its admin to start with - you can't test anything otherwise.
    // Tests then can create additional repos and users to test the boundaries of repository and user separation.
    testContext.persistRepository()

    // nuke the data dir tree
    IntegrationTestUtil.createFileStoreDir(testApp)
  }

  /**
   * The IDs among more unknown ones than a PostgreSQL statement takes parameters for (65,535): a selection that only a statement
   * binding its ID set as one value can run
   */
  def amongManyUnknownIds(ids: Set[String]): Set[String] =
    ids ++ (1 to 70000).map(n => f"unknown-$n%028d")

  def switchContextUser(user: User): Unit = {
    testApp.service.user.switchContextToUser(user)
  }

  def switchContextRepo(repository: Repository): Unit = {
    testApp.service.repository.switchContextToRepository(repository)
  }

  /**
   * Starts `f` on a thread of its own, which inherits the test's repository and account but not its transaction. Returns the
   * thread and what `f` threw, which is set by the time the thread is joined.
   */
  protected def onAnotherThread(f: => Unit): (Thread, AtomicReference[Option[Throwable]]) = {
    val failure = new AtomicReference[Option[Throwable]](None)
    val thread = new Thread(
      () =>
        try f
        catch { case ex: Throwable => failure.set(Some(ex)) })
    thread.start()
    (thread, failure)
  }

  /**
   * Runs `first` and `second` on threads of their own, each in its transaction, `first` holding its transaction open until
   * `second` waits for it: for a row lock on PostgreSQL, whose backend is identified by its process ID, and for the one write
   * connection on SQLite, whose waiter is the thread parked in the pool.
   */
  def contend(first: => Unit, second: => Unit): Unit = {
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val secondBackend = new AtomicReference[Option[AnyRef]](None)

    val (firstThread, firstFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        first
        holding.countDown()
        release.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      holding.await(30, TimeUnit.SECONDS) shouldBe true

      val (secondThread, secondFailure) = onAnotherThread {
        testApp.txManager.withTransaction {
          if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
            secondBackend.set(Some(query("SELECT pg_backend_pid() AS pid").head("pid")))
          }
          second
        }
      }

      // pg_stat_activity is read once per transaction, so every poll is a transaction of its own
      def secondIsWaiting: Boolean = testApp.dataSourceType match {
        case Const.DbEngineName.POSTGRES =>
          secondBackend.get.exists(
            pid =>
              testApp.txManager.asReadOnly {
                query("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid).exists(_("wait_event_type") == "Lock")
              })
        case _ => secondThread.getState == Thread.State.TIMED_WAITING || secondThread.getState == Thread.State.WAITING
      }

      Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(20, Millis))) {
        secondIsWaiting shouldBe true
      }
      release.countDown()
      secondThread.join()
      secondFailure.get.foreach(throw _)
    } finally {
      release.countDown()
      firstThread.join()
    }
    firstFailure.get.foreach(throw _)
  }

  /** Runs `f` with the Setting saved as `value`, and saves its default back afterwards, so the other suites see the seed */
  def withSetting[T](name: String, value: String)(f: => T): T = {
    testApp.settings.save(Map(name -> value))
    try f
    finally testApp.settings.save(Map(name -> testApp.settings.current.default(name)))
  }

  /** The context repository's six stored stats by dimension, without the totals, which are summed on read */
  def storedStats: Map[String, Long] =
    testApp.service.stats.getStats.stats
      .filterNot(stat => stat.dimension == Stats.TOTAL_ASSETS || stat.dimension == Stats.TOTAL_BYTES)
      .map(stat => stat.dimension -> stat.dimVal)
      .toMap
}
