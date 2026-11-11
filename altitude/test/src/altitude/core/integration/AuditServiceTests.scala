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
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.models.AccountSnapshot
import altitude.core.models.AuditEventType
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

    val stored = testApp.service.audit.getById(recorded.persistedId)
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
    val storedFailure = testApp.service.audit.getById(failedLogin.persistedId)
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

    an[NotFoundException] should be thrownBy testApp.service.audit.getById(rolledBackId.get)
    testApp.service.audit.getById(committedId.get).event shouldBe AuditEventType.PasswordSet
    logged.count(_.contains("password_set")) shouldBe 1
  }
}
