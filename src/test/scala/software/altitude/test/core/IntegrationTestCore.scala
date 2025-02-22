package software.altitude.test.core
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core._
import software.altitude.core.actors.FaceRecManagerActor
import software.altitude.core.actors.FaceRecModelActor.ModelLabels
import software.altitude.core.actors.FaceRecModelActor.ModelSize
import software.altitude.core.models._
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.integration.TestContext

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

abstract class IntegrationTestCore
    extends funsuite.AnyFunSuite
    with testAltitudeApp
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
      new QueryRunner().query(RequestContext.getConn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object]): _*).asScala.toList

    res.map(_.asScala.toMap[String, AnyRef])
  }

  override def beforeEach(): Unit = {
    AltitudeServletContext.clearState()
    AltitudeServletContext.app.isInitialized = false
    testContext = new TestContext(testApp)

    // Every integration test has at least one repository and its admin to start with - you can't test anything otherwise.
    // Tests then can create additional repos and users to test the boundaries of repository and user separation.
    testContext.persistRepository()

    // Clear the face recognition model before each test
    testApp.service.faceRecognition.initialize()

    // Clear face recognition cache
    testApp.service.faceCache.clear()

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

  /**
   * The number of labels in the model, minus the reserved labels.
   * That is, this reflects purely our trained labels for easier reasoning about the counts.
   */
  def getNumberOfModelLabels: Int = {
    val repositoryId = RequestContext.getRepository.persistedId
    val futureResp: Future[ModelSize] = testApp.actorSystem ? (ref => FaceRecManagerActor.GetModelSize(repositoryId, ref))
    Await.result(futureResp, timeout.duration).size
  }

  def getLabels: Seq[Int] = {
    val repositoryId = RequestContext.getRepository.persistedId
    val futureResp: Future[ModelLabels] = testApp.actorSystem ? (ref => FaceRecManagerActor.GetModelLabels(repositoryId, ref))
    Await.result(futureResp, timeout.duration).labels
  }
}
