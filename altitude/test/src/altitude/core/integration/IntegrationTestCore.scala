package altitude.core.integration

import altitude.core.*
import altitude.core.models.*
import altitude.test.{IntegrationTestUtil, TestContext, TestFocus}
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.*
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

abstract class IntegrationTestCore
  extends funsuite.AnyFunSuite
    with AltitudeTestApp
    with BeforeAndAfter
    with BeforeAndAfterEach
    with OptionValues
    with TestFocus {
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  var testContext: TestContext = new TestContext(testApp)

  implicit val scheduler: Scheduler = testApp.actorSystem.scheduler
  implicit val timeout: Timeout = 3.seconds

  def query(sql: String, values: Any*): List[Map[String, AnyRef]] = {
    val res =
      new QueryRunner().query(RequestContext.getConn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object])*).asScala.toList

    res.map(_.asScala.toMap[String, AnyRef])
  }

  def update(sql: String, values: Any*): Unit = {
    new QueryRunner().update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
  }

  def getSqlDateTime(t: java.sql.Timestamp): Any = {
    testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES => t
      case  Const.DbEngineName.SQLITE => t.toString
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

    // Clear the face recognition model before each test
    testApp.service.faceRecognition.initialize()

    // nuke the data dir tree
    IntegrationTestUtil.createFileStoreDir(testApp)
  }

  def switchContextUser(user: User): Unit = {
    testApp.service.user.switchContextToUser(user)
  }

  def switchContextRepo(repository: Repository): Unit = {
    testApp.service.repository.switchContextToRepository(repository)
  }

  /**
   * Converts a function with no arguments to a Mockito `Answer`.
   * This method is used when creating a Mockito `Answer` that does not require any information from the `InvocationOnMock`.
   *
   * @param f A function that takes no arguments and returns a value of type `T`.
   * @return An `Answer[T]` that, when invoked, calls the provided function `f`.
   */
  implicit def toAnswer[T](f: () => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f()
  }

  /**
   * Converts a function with an `InvocationOnMock` argument to a Mockito `Answer`.
   * This method is used when creating a Mockito `Answer` that requires information from the `InvocationOnMock`.
   *
   * @param f A function that takes an `InvocationOnMock` and returns a value of type `T`.
   * @return An `Answer[T]` that, when invoked, calls the provided function `f` with the `InvocationOnMock`.
   */
  implicit def toAnswerWithArguments[T](f: InvocationOnMock => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f(invocation)
  }
}

