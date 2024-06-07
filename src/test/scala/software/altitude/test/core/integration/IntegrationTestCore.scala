package software.altitude.test.core.integration

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import net.codingwell.scalaguice.InjectorExtensions._
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.io.FileUtils
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import software.altitude.core.Const
import software.altitude.core.models._
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C, _}
import software.altitude.test.core.TestFocus
import software.altitude.test.core.integration.util.dao.UtilitiesDao
import software.altitude.test.core.integration.util.dao.jdbc
import software.altitude.test.core.suites.PostgresSuite
import software.altitude.test.core.suites.SqliteSuite

import java.io.File
import scala.language.implicitConversions


object IntegrationTestCore {
  def createTestDir(altitude: AltitudeCoreApp): Unit = {
    val testDir = new File(altitude.config.getString("testDir"))

    if (!testDir.exists()) {
      FileUtils.forceMkdir(testDir)
    }
  }

  def createFileStoreDir(altitude: AltitudeCoreApp): Unit = {
    val testDir = new File(altitude.config.getString("fileStoreDir"))

    if (testDir.exists()) {
      FileUtils.cleanDirectory(testDir)
    }
    else {
      FileUtils.forceMkdir(testDir)
    }
  }
}

trait AnswerSugar {
  // Sugar for Mockito `doAnswer` calls
  implicit def toAnswer[T](f: () => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f()
  }

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

  // Stores test app config overrides, since we run same tests with a different app setup.
  def config: Map[String, Any]

  // Force environment to always be TEST
  Environment.ENV = Environment.TEST

  final val datasource: Const.DatasourceType.Value = config("datasource").asInstanceOf[C.DatasourceType.Value]

  protected def altitude: Altitude = datasource match {
    case C.DatasourceType.POSTGRES => PostgresSuite.app
    case C.DatasourceType.SQLITE => SqliteSuite.app
    case _ => throw new IllegalArgumentException(s"Do not know of datasource: $datasource")
  }

  /**
   * Inject DB utilities, based on current data source
   */
  final val injector: Injector = Guice.createInjector(new InjectionModule)
  final protected val dbUtilities: UtilitiesDao = injector.instance[UtilitiesDao]

  /**
   * Extremely important! This is the one and only transaction id for tests.
   * This has to be controlled by us here, and always be implicitly defined.
   * The transaction managers will not commit transactions if it's an existing
   * transaction ID. This allows us to rollback every test, keeping the database
   * clean.
   */
  implicit val txId: TransactionId = new TransactionId

  /**
   * Our test users. We may alternate between them to make sure there is proper
   * separation between data users are allowed to see.
   */
  private var user: User = null
  private var secondUser: User = null

  var currentUser: User = user

  /**
   * Just as with users, we need at least two repositories to make sure repository
   * bounds are not broken. Normally, no request should ever be able to peek into
   * data from other repositories. This is enforced in the DAO layer.
   */
  private var repo: Repository = null
  private var secondRepo: Repository = null

  var currentRepo: Repository = repo

  /**
   * Our implicit context for all tests.
   * Note that it is a function, so it will dynamically set the current
   * user and repository.
   */
  implicit final def ctx: Context = new Context(repo = currentRepo, user = currentUser)

  /**
   * Methods to toggle between different user and repositories.
   */
  def SET_FIRST_USER(): Unit = {
    currentUser = user
  }
  def SET_SECOND_USER(): Unit = {
    currentUser = secondUser
  }

  def SET_FIRST_REPO(): Unit = {
    currentRepo = repo
  }

  def SET_SECOND_REPO(): Unit = {
    currentRepo = secondRepo
  }

  /**
   * A helper method to quickly cook a test asset
   */
  protected def makeAsset(folder: Folder, metadata: Metadata = Metadata()): Asset = Asset(
    userId = currentUser.id.get,
    folderId = folder.id.get,
    assetType = new AssetType(
      mediaType = "mediaType", mediaSubtype = "mediaSubtype", mime = "mime"),
    fileName = Util.randomStr(50),
    path = Some(Util.randomStr(50)),
    checksum = Util.randomStr(32),
    metadata = metadata,
    sizeBytes = 1000L)

  /**
   * Convert a file system resource to an import asset
   */
  def fileToImportAsset(file: File): ImportAsset = new ImportAsset(
    fileName = file.getName,
    path = file.getAbsolutePath,
    sourceType = C.FileStoreType.FS,
    data = FileUtils.readFileToByteArray(file),
    metadata = Metadata())

  // test count - we use it as a request ID for our logging environment
  private var count = 0

  override def beforeEach(): Unit = {
    // this is for logging context
    MDC.put("USER", s"[USR:$currentUser]")
    count = count + 1
    MDC.put("REQUEST_ID", s"[TEST: $count]")

    IntegrationTestCore.createFileStoreDir(altitude)
    createFixtures()

    // keep transaction stats clean after DB migration dirties them
    altitude.txManager.transactions.reset()

    dbUtilities.createTransaction(txId)

    SET_FIRST_USER()
    SET_FIRST_REPO()
  }

  def savepoint(): Unit = {
    altitude.txManager.savepoint()
  }

  override def afterEach(): Unit = {
    dbUtilities.cleanupTest()

    if (datasource == C.DatasourceType.SQLITE || datasource == C.DatasourceType.POSTGRES) {
      // should not have committed anything for tests
      require(altitude.txManager.transactions.COMMITTED == 0)
      // should only have had one transaction - if this fails, implicit transaction logic is likely broken
      if (altitude.txManager.transactions.CREATED != 1) {
        log.error(s"${altitude.txManager.transactions.CREATED} transactions instead of 1!")
      }
      require(altitude.txManager.transactions.CREATED == 1)
    }
  }

  class InjectionModule extends AbstractModule with ScalaModule  {
    override def configure(): Unit = {
      log.info(s"Datasource type: ${altitude.dataSourceType}", C.LogTag.APP)

      altitude.dataSourceType match {
        case C.DatasourceType.POSTGRES | C.DatasourceType.SQLITE =>
          bind[UtilitiesDao].toInstance(new jdbc.UtilitiesDao(altitude))
        case _ => throw new IllegalArgumentException(s"Do not know of datasource: ${altitude.dataSourceType}")
      }
    }
  }

  def createFixtures(): Unit = {
    user = altitude.service.user.add(User())
    secondUser = altitude.service.user.add(User())

    repo = altitude.service.repository.addRepository(
      name = "Test Repository 1",
      fileStoreType = C.FileStoreType.FS,
      user = user)

    secondRepo = altitude.service.repository.addRepository(
      name = "Test Repository 2",
      fileStoreType = C.FileStoreType.FS,
      user = secondUser)
  }
}
