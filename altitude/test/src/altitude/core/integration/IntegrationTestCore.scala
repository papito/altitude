package altitude.core.integration

import altitude.test.{ IntegrationTestUtil, TestContext, TestFocus }
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.*
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

import altitude.core.*
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

  def getSqlDateTime(t: java.sql.Timestamp): Any = {
    testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES => t
      case Const.DbEngineName.SQLITE => t.toString
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

  /** The context repository's six stored stats by dimension, without the totals, which are summed on read */
  def storedStats: Map[String, Long] =
    testApp.service.stats.getStats.stats
      .filterNot(stat => stat.dimension == Stats.TOTAL_ASSETS || stat.dimension == Stats.TOTAL_BYTES)
      .map(stat => stat.dimension -> stat.dimVal)
      .toMap
}
