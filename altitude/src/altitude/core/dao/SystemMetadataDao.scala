package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.SystemMetadata

object SystemMetadataDao {
  // we only have one record in the system table at all times
  val SYSTEM_RECORD_ID = 1
}

trait SystemMetadataDao extends BaseDao[SystemMetadata] {
  def updateVersion(toVersion: Int): Unit
  def setInitialized(): Unit
}
