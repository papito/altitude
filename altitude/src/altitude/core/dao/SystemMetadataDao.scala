package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.SystemMetadata

object SystemMetadataDao:
  // The system table holds one row
  val SYSTEM_RECORD_ID = 1

trait SystemMetadataDao extends BaseDao[SystemMetadata]:
  /** Stamps the schema version on the system row; `IllegalStateException` when there is no row to stamp */
  def updateVersion(toVersion: Int): Unit

  /** Marks the instance set up unless it is already; whether this call marked it */
  def setInitialized(): Boolean

  /** The Base64 key of signed content URLs */
  def getSigningKey: Option[String]

  /** Stores the key unless one is stored already; whether this call stored it */
  def setSigningKey(key: String): Boolean
