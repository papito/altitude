package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AuditEvent

/**
 * The append-only audit table, written through `add` and read through the generic reads. An event is written with or without a
 * Library in the context (a login has none), so the DAO is not scoped to one, and nothing updates or deletes a row.
 */
trait AuditEventDao extends BaseDao[AuditEvent]
