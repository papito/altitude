package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AuditEvent

/** The append-only audit table: nothing updates or deletes a row. Not scoped to a Library, since a login has none. */
trait AuditEventDao extends BaseDao[AuditEvent]
