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
 * The audit log of account events. An event is written in the caller's transaction, so it commits or rolls back with the change
 * it records, and is logged only after commit. The table is append-only, so the service has no generic writes.
 */
class AuditService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: AuditEventDao = app.DAO.auditEvent
  private val txManager: TransactionManager = app.txManager

  /**
   * `actor` is None for a failed login, which names no account. Actor and target are snapshotted, since either may change or be
   * deleted later.
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
