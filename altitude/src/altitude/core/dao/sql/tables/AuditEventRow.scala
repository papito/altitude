package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `audit_event` table. `actor`, `target` and `detail` are JSON text: `jsonb` on PostgreSQL, `TEXT` on SQLite. */
case class AuditEventRow[T[_]](
    id: T[String],
    event: T[String],
    actor: T[Option[String]],
    target: T[Option[String]],
    detail: T[Option[String]],
    clientIp: T[Option[String]],
    createdAt: T[OffsetDateTime])

object AuditEventRow extends Table[AuditEventRow]:
  override def tableName: String = "audit_event"
