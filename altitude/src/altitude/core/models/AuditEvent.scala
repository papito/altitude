package altitude.core.models

import java.time.LocalDateTime

/** What an audit event records. Persist `dbValue`, never the enum name. */
enum AuditEventType(val dbValue: String):
  case AccountCreated extends AuditEventType("account_created")
  case AccountEdited extends AuditEventType("account_edited")
  case PermissionsChanged extends AuditEventType("permissions_changed")
  case AccountTypeChanged extends AuditEventType("account_type_changed")
  case AccountDeactivated extends AuditEventType("account_deactivated")
  case AccountReactivated extends AuditEventType("account_reactivated")
  case AccountDeleted extends AuditEventType("account_deleted")
  case PasswordSet extends AuditEventType("password_set")
  // An account's change of its own password refused for a wrong current password
  case PasswordChangeFailed extends AuditEventType("password_change_failed")
  case LoginSucceeded extends AuditEventType("login_succeeded")
  case LoginFailed extends AuditEventType("login_failed")

object AuditEventType:
  def fromDbValue(value: String): AuditEventType =
    values.find(_.dbValue == value).getOrElse(throw IllegalArgumentException(s"Unknown audit event: $value"))

/**
 * One row of the append-only `audit_event` table. The actor is who acted (none for a failed login, which names no account), the
 * target the account acted on, both as they were then; `detail` says what changed, and `clientIp` where a request came from.
 */
final case class AuditEvent(
    id: Option[String] = None,
    event: AuditEventType,
    actor: Option[AccountSnapshot],
    target: Option[AccountSnapshot] = None,
    detail: ujson.Obj = ujson.Obj(),
    clientIp: Option[String] = None,
    createdAt: Option[LocalDateTime] = None)
  extends BaseModel:

  val updatedAt: Option[LocalDateTime] = None

  override def toString: String =
    val who = actor.fold("nobody")(a => s"[${a.id}] @${a.username}")
    val whom = target.fold("")(t => s" on [${t.id}] @${t.username}")
    val from = clientIp.fold("")(ip => s" from [$ip]")
    s"Audit: ${event.dbValue} by $who$whom$from ${ujson.write(detail)}"
