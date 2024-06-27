package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao

object SystemMetadataDao {
  // we only have one record in the system table at all times
  val SYSTEM_RECORD_ID = 1
}


trait SystemMetadataDao extends BaseDao {
  def updateVersion(toVersion: Int): Unit
  def setInitialized(): Unit
}
