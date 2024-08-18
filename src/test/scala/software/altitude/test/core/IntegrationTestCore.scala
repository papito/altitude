package software.altitude.test.core
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core._
import software.altitude.core.models._
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.integration.TestContext

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

  override def afterEach(): Unit = {
    // We COULD rollback here, but we don't need to, and committing is better for checking repo/user isolation
    testApp.txManager.commit()
  }

  def savepoint(): Unit = {
    testApp.txManager.savepoint()
  }

  def switchContextUser(user: User): Unit = {
    testApp.service.user.switchContextToUser(user)
  }

  def switchContextRepo(repository: Repository): Unit = {
    testApp.service.repository.switchContextToRepository(repository)
  }

  def commit(): Unit = {
    testApp.txManager.commit()
  }

  def rollback(): Unit = {
    testApp.txManager.rollback()
  }

  def reset(): Unit = {
    testApp.txManager.rollback()
    RequestContext.clear()
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
  def getNumberOfModelLabels: Int = testApp.service.faceRecognition.recognizer.getLabels.size().height.toInt - 2

  def getLabels: Seq[Int] = {
    val labels = testApp.service.faceRecognition.recognizer.getLabels
    (0 until labels.height()).map(labels.get(_, 0)(0).toInt)
  }
}
