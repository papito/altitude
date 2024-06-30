package software.altitude.test.core

import org.apache.commons.io.FileUtils
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import software.altitude.core.Const
import software.altitude.core.models._
import software.altitude.core.{Const => C, _}
import software.altitude.test.core.integration.TestContext
import software.altitude.test.core.suites.PostgresSuiteBundle
import software.altitude.test.core.suites.SqliteSuiteBundle

import java.io.File
import scala.language.implicitConversions


object IntegrationTestCore {
  def createTestDir(altitude: AltitudeAppContext): Unit = {
    val testDir = new File(altitude.config.getString("testDir"))

    if (!testDir.exists()) {
      FileUtils.forceMkdir(testDir)
    }
  }

  def createFileStoreDir(altitude: AltitudeAppContext): Unit = {
    val dataDir = new File(altitude.config.getString("dataDir"))

    if (dataDir.exists()) {
      FileUtils.cleanDirectory(dataDir)
    }
    else {
      FileUtils.forceMkdir(dataDir)
    }
  }

  /**
   * Convert a file system resource to an import asset
   */
  // FIXME: should be moved to a more appropriate location (test utils)
  def fileToImportAsset(file: File): ImportAsset = new ImportAsset(
    fileName = file.getName,
    data = FileUtils.readFileToByteArray(file),
    metadata = Metadata())
}

/**
 * The trait provides utility methods for creating Mockito `Answer` instances.
 * These methods simplify the creation of `Answer` instances by allowing the use of lambda expressions.
 */
// FIXME: also test utils
trait AnswerSugar {
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
  implicit def toAnswerWithArguments[T](f: (InvocationOnMock) => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f(invocation)
  }
}

abstract class IntegrationTestCore
  extends funsuite.AnyFunSuite
    with BeforeAndAfter
    with BeforeAndAfterEach
    with OptionValues
    with AnswerSugar
    with TestFocus {
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  override def beforeEach(): Unit = {
    AltitudeServletContext.app.isInitialized = false
    testContext = new TestContext(altitude)

    /*
     Every integration test has at least one repository to start with - you can't do anything otherwise
     This also creates the first user (owner of the repository).

     Tests then can create additional repos and users to test the boundaries of repository and user separation.
     */
    testContext.persistRepository()

    // this is for logging context
    MDC.put("USER", s"[USR:$testContext.user]")
    count = count + 1
    MDC.put("REQUEST_ID", s"[TEST: $count]")

    IntegrationTestCore.createFileStoreDir(altitude)
  }

  override def afterEach(): Unit = {
    altitude.txManager.commit()
  }

  // Stores test app config overrides, since we run same tests with a different app setup.
  def config: Map[String, Any]

  // Force environment to always be TEST
  Environment.ENV = Environment.TEST

  final val datasource: Const.DatasourceType.Value = config("datasource").asInstanceOf[C.DatasourceType.Value]

  protected def altitude: Altitude = datasource match {
    case C.DatasourceType.POSTGRES => PostgresSuiteBundle.app
    case C.DatasourceType.SQLITE => SqliteSuiteBundle.app
    case _ => throw new IllegalArgumentException(s"Do not know of datasource: $datasource")
  }

  var testContext: TestContext = new TestContext(altitude)

  // test count - we use it as a request ID for our logging environment
  private var count = 0

  def savepoint(): Unit = {
    altitude.txManager.savepoint()
  }

  def switchContextUser(user: User): Unit = {
    altitude.service.user.switchContextToUser(user)
  }

  def switchContextRepo(repository: Repository): Unit = {
    altitude.service.repository.switchContextToRepository(repository)
  }

  def reset(): Unit = {
    altitude.txManager.rollback()
    RequestContext.clear()
  }
}
