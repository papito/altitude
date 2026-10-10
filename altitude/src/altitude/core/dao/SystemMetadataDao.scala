package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.SystemMetadata

object SystemMetadataDao:
  // we only have one record in the system table at all times
  val SYSTEM_RECORD_ID = 1

trait SystemMetadataDao extends BaseDao[SystemMetadata]:
  def updateVersion(toVersion: Int): Unit
  def setInitialized(): Unit

  /** The Base64 key of signed content URLs, if one was stored */
  def getSigningKey: Option[String]

  /** Stores the Base64 key of signed content URLs unless one is stored already; whether this call stored it */
  def setSigningKey(key: String): Boolean
