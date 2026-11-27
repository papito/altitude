package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.tables.SystemRow
import altitude.core.models.SystemMetadata

abstract class SystemMetadataDao(override val config: Config)
  extends BaseDao[SystemMetadata]
  with altitude.core.dao.SystemMetadataDao:

  override val tableName = "system"

  // The table has no repository_id
  final override protected val repositoryScoped = false

  final override type Row[T[_]] = SystemRow[T]
  final override protected def table: Table[Row] = SystemRow

  override protected def toModel(row: SystemRow[Sc]): SystemMetadata =
    SystemMetadata(version = row.version, isInitialized = row.isInitialized)

  override protected def makeModel(rec: Map[String, AnyRef]): SystemMetadata = SystemMetadata(
    version = rec(FieldConst.SystemMetadata.VERSION).asInstanceOf[Int],
    isInitialized = getBooleanField(rec(FieldConst.SystemMetadata.IS_INITIALIZED))
  )

  def updateVersion(toVersion: Int): Unit =
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.VERSION} = ? WHERE id = ?"
    logger.trace(s"Setting the schema version to $toVersion")
    val stamped = updateByBySql(sql, List(toVersion, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID))
    // A schema with no row stamped would read as a fresh database at the next start, and get the schema script run over it
    if stamped != 1 then throw IllegalStateException(s"No system row was stamped with schema version $toVersion")

  // Only while the instance is not set up, so of two setups at once the second waits on the row (PostgreSQL) and then matches
  // nothing; SQLite runs writers one at a time
  def setInitialized(): Boolean =
    val column = FieldConst.SystemMetadata.IS_INITIALIZED
    val sql = s"UPDATE system SET $column = ? WHERE id = ? AND $column = ?"
    updateByBySql(sql, List(true, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID, false)) == 1

  def setUninitialized(): Unit =
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"
    updateByBySql(sql, List(false, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)): Unit

  def getSigningKey: Option[String] =
    val sql = s"SELECT ${FieldConst.SystemMetadata.SIGNING_KEY} FROM system WHERE id = ?"
    val rec = executeAndGetOne(sql, List(altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID))
    Option(rec(FieldConst.SystemMetadata.SIGNING_KEY).asInstanceOf[String])

  // Only while the column is NULL, so of two instances starting at once the second writes nothing and adopts the first one's key
  def setSigningKey(key: String): Boolean =
    val column = FieldConst.SystemMetadata.SIGNING_KEY
    val sql = s"UPDATE system SET $column = ? WHERE id = ? AND $column IS NULL"
    updateByBySql(sql, List(key, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)) == 1

  // The one row's ID is an integer, bound as one
  override def getById(id: String): SystemMetadata =
    val sql: String = """
      SELECT *
        FROM system
       WHERE id = ?
     """
    getOneBySql(sql, List(id.toInt))
