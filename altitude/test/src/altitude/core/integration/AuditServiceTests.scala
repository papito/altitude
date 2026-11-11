package altitude.core.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ an, be, shouldBe, thrownBy }
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.models.AccountSnapshot
import altitude.core.models.AccountType
import altitude.core.models.AuditEvent
import altitude.core.models.AuditEventType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.User
import altitude.core.service.AuditService

@DoNotDiscover class AuditServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val auditLogger = LoggerFactory.getLogger(classOf[AuditService]).asInstanceOf[Logger]
  private var appender: Option[ListAppender[ILoggingEvent]] = None

  /** The INFO lines the audit log has written while the capture of [[infoLogged]] runs */
  private def infoLoggedSoFar: List[String] =
    appender.toList.flatMap(_.list.asScala).filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)

  /** The INFO lines `AuditService` logs while the block runs; test logging is off, so the level is raised for the block */
  private def infoLogged(block: => Unit): List[String] = {
    val capture = ListAppender[ILoggingEvent]()
    val level = auditLogger.getLevel
    capture.start()
    auditLogger.addAppender(capture)
    auditLogger.setLevel(Level.INFO)
    appender = Some(capture)
    try block
    finally {
      auditLogger.detachAppender(capture)
      auditLogger.setLevel(level)
    }
    val lines = infoLoggedSoFar
    appender = None
    lines
  }

  /** The stored event with the ID, read through the DAO: the service reads no event */
  private def storedEvent(id: String): AuditEvent = testApp.txManager.asReadOnly(testApp.DAO.auditEvent.getById(id))

  /** The stored events of `event` naming the account as their target, oldest first */
  private def eventsOn(account: User, event: AuditEventType): List[AuditEvent] =
    testApp.txManager.asReadOnly {
      query("SELECT id FROM audit_event WHERE event = ? ORDER BY created_at", event.dbValue)
        .map(row => testApp.DAO.auditEvent.getById(row("id").toString.trim))
        .filter(_.target.exists(_.id == account.persistedId))
    }

  test("An event is stored with its actor and target as they were, with no Library in the context") {

    /**
     * Setup:
     *
     * An Admin and a User, and no Library in the request context; the Admin's edit of the User is recorded with a detail and a
     * client IP, then the User's name changes.
     *
     * Assertions:
     *
     * The event reads back by its ID with its type, the Admin and the User as snapshots (ID, name, username and email) taken when
     * it was recorded, the detail, the client IP and a creation time.
     *
     * Edge cases:
     *
     * A failed login is recorded with no actor and no target.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val user = testContext.persistUser()
    RequestContext.repository.value = None

    val recorded = testApp.service.audit.record(
      AuditEventType.AccountEdited,
      actor = Some(admin),
      target = Some(user),
      detail = ujson.Obj("name" -> ujson.Obj("from" -> user.name, "to" -> "Renamed")),
      clientIp = Some("192.0.2.7")
    )
    testApp.service.user.updateById(user.persistedId, Map("name" -> "Renamed"))

    val stored = storedEvent(recorded.persistedId)
    stored.event shouldBe AuditEventType.AccountEdited
    stored.actor shouldBe Some(AccountSnapshot(admin.persistedId, admin.name, admin.username, admin.email))
    stored.target shouldBe Some(AccountSnapshot(user.persistedId, user.name, user.username, user.email))
    stored.detail shouldBe ujson.Obj("name" -> ujson.Obj("from" -> user.name, "to" -> "Renamed"))
    stored.clientIp shouldBe Some("192.0.2.7")
    stored.createdAt.isDefined shouldBe true

    val failedLogin = testApp.service.audit.record(
      AuditEventType.LoginFailed,
      actor = None,
      detail = ujson.Obj("login" -> "nobody"),
      clientIp = Some("192.0.2.8"))
    val storedFailure = storedEvent(failedLogin.persistedId)
    storedFailure.actor shouldBe None
    storedFailure.target shouldBe None
    storedFailure.detail shouldBe ujson.Obj("login" -> "nobody")
  }

  test("An event recorded in a transaction that rolls back is neither stored nor logged; a committed one is logged once") {

    /**
     * Setup:
     *
     * A User's password change recorded inside a transaction that then fails, and another recorded inside one that commits, with
     * the audit log's INFO lines captured.
     *
     * Assertions:
     *
     * The first event is not found once its transaction has rolled back, and no INFO line names it: it commits or rolls back with
     * the change it records. The second is stored, and logged at INFO once, after its transaction commits.
     */
    val user = testContext.persistUser()
    var rolledBackId: Option[String] = None
    var committedId: Option[String] = None

    val logged = infoLogged {
      an[IllegalStateException] should be thrownBy testApp.txManager.withTransaction {
        rolledBackId = testApp.service.audit.record(AuditEventType.PasswordSet, actor = Some(user), target = Some(user)).id
        throw IllegalStateException("The change failed")
      }

      testApp.txManager.withTransaction {
        committedId = testApp.service.audit.record(AuditEventType.PasswordSet, actor = Some(user), target = Some(user)).id
        infoLoggedSoFar shouldBe Nil
      }
    }

    an[NotFoundException] should be thrownBy storedEvent(rolledBackId.get)
    storedEvent(committedId.get).event shouldBe AuditEventType.PasswordSet
    logged.count(_.contains("password_set")) shouldBe 1
  }

  test("Setting the system up records the creation of its Admin") {

    /**
     * Setup:
     *
     * A system initialization with a new Admin, a Library and a password, from a client IP.
     *
     * Assertions:
     *
     * One account creation is recorded on the new Admin, acted by the Admin itself, with its account type, the setup named in its
     * detail, and the client IP.
     */
    RequestContext.clear()
    val (admin, _) = testApp.service.system.initializeSystem(
      repositoryName = s"Set up ${altitude.core.util.Util.randomStr()}",
      adminModel = testContext.makeAdminUser(),
      password = "password3000",
      clientIp = Some("192.0.2.10")
    )

    val List(created) = eventsOn(admin, AuditEventType.AccountCreated)
    created.actor.map(_.id) shouldBe Some(admin.persistedId)
    created.detail shouldBe ujson.Obj("accountType" -> AccountType.Admin.toString, "setup" -> true)
    created.clientIp shouldBe Some("192.0.2.10")
  }

  test("Each grant a new Library gives is recorded as a change of Permissions, acted by the Library's creator") {

    /**
     * Setup:
     *
     * A User whose new-Library switch is on with Import, and a User with the Create Libraries flag, who creates a Library.
     *
     * Assertions:
     *
     * Each of the two accounts has one change of Permissions recorded, acted by the creator, naming the new Library with the
     * Permissions it was given there: Import for the first, every Permission for the creator.
     *
     * Edge cases:
     *
     * A Library added with no creator (as setup adds one) records its grants with no actor.
     */
    val switchedOn = testContext.persistUser(
      Some(testContext.makeUser().copy(grantsNewLibraries = true, newLibraryPermissions = Permissions.of(Permission.Import))))
    val creator = testContext.persistUser(Some(testContext.makeUser().copy(canCreateLibraries = true)))

    val library = testApp.service.repository.addRepository(
      s"Granted ${altitude.core.util.Util.randomStr()}",
      C.StorageEngineName.FS,
      creatorId = Some(creator.persistedId))

    def detail(permissions: Permissions) =
      ujson.Obj("library" -> library.persistedId, "permissions" -> ujson.Arr.from(permissions.toList.map(_.toString)))
    val List(granted) = eventsOn(switchedOn, AuditEventType.PermissionsChanged)
    granted.actor.map(_.id) shouldBe Some(creator.persistedId)
    granted.detail shouldBe detail(Permissions.of(Permission.Import))
    val List(own) = eventsOn(creator, AuditEventType.PermissionsChanged)
    own.actor.map(_.id) shouldBe Some(creator.persistedId)
    own.detail shouldBe detail(Permissions.all)

    testApp.service.repository.addRepository(s"Granted ${altitude.core.util.Util.randomStr()}", C.StorageEngineName.FS)
    val actors = eventsOn(switchedOn, AuditEventType.PermissionsChanged).map(_.actor.map(_.id))
    actors.size shouldBe 2
    actors.toSet shouldBe Set(Some(creator.persistedId), None)
  }
}
