package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.dao.AuditEventDao
import altitude.core.models.AccountSnapshot
import altitude.core.models.AuditEvent
import altitude.core.models.AuditEventType
import altitude.core.models.User
import altitude.core.transactions.TransactionManager

/**
 * The audit log: one place every account event is written through, with or without a Library in the context. An event goes into
 * the append-only `audit_event` table, in the caller's transaction when there is one, so it commits or rolls back with the change
 * it records, and is logged at INFO once that transaction has committed (`TransactionManager.afterCommit`), so a rolled-back
 * event is never logged. Nothing updates or deletes an event, so the service has no generic writes.
 */
class AuditService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: AuditEventDao = app.DAO.auditEvent
  private val txManager: TransactionManager = app.txManager

  /**
   * Records `event`: `actor` is the account that acted (None for a failed login, which names no account) and `target` the account
   * acted on, both kept as they are now (`AccountSnapshot`), since either may change or be deleted later; `detail` says what
   * changed, and `clientIp` where the request came from. The stored event.
   */
  def record(
      event: AuditEventType,
      actor: Option[User],
      target: Option[User] = None,
      detail: ujson.Obj = ujson.Obj(),
      clientIp: Option[String] = None): AuditEvent =
    txManager.withTransaction {
      val stored = dao.add(
        AuditEvent(
          event = event,
          actor = actor.map(AccountSnapshot.of),
          target = target.map(AccountSnapshot.of),
          detail = detail,
          clientIp = clientIp))
      txManager.afterCommit(logger.info(stored.toString))
      stored
    }

  /** The event with the ID, `NotFoundException` when there is none */
  def getById(id: String): AuditEvent =
    txManager.asReadOnly {
      dao.getById(id)
    }
