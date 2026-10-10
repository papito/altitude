package altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.SystemRow
import altitude.core.models.SystemMetadata

abstract class SystemMetadataDao(override val config: Config)
  extends BaseDao[SystemMetadata]
  with altitude.core.dao.SystemMetadataDao:

  override val tableName = "system"

  final override type Row[T[_]] = SystemRow[T]
  final override protected def table: Table[Row] = SystemRow

  override protected def toModel(row: SystemRow[Sc]): SystemMetadata =
    SystemMetadata(version = row.version, isInitialized = row.isInitialized)

  override protected def makeModel(rec: Map[String, AnyRef]): SystemMetadata = SystemMetadata(
    version = rec(FieldConst.SystemMetadata.VERSION).asInstanceOf[Int],
    isInitialized = getBooleanField(rec(FieldConst.SystemMetadata.IS_INITIALIZED))
  )

  def updateVersion(toVersion: Int): Unit =
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.VERSION} = ? WHERE id = ?"
    logger.trace(s"UPDATE SQL: $sql, to version $toVersion")
    runner.update(RequestContext.getConn, sql, toVersion, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)

  def setInitialized(): Unit =
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"
    logger.trace(s"UPDATE SQL: $sql, initialized")
    runner.update(RequestContext.getConn, sql, true, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)

  def setUninitialized(): Unit =
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"
    logger.trace(s"UPDATE SQL: $sql, uninitialized")
    runner.update(RequestContext.getConn, sql, false, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)

  def getSigningKey: Option[String] =
    val sql = s"SELECT ${FieldConst.SystemMetadata.SIGNING_KEY} FROM system WHERE id = ?"
    val rec = executeAndGetOne(sql, List(altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID))
    Option(rec(FieldConst.SystemMetadata.SIGNING_KEY).asInstanceOf[String])

  // Only while the column is NULL, so of two instances starting at once the second writes nothing and adopts the first one's key
  def setSigningKey(key: String): Boolean =
    val runner: QueryRunner = new QueryRunner()
    val column = FieldConst.SystemMetadata.SIGNING_KEY
    val sql = s"UPDATE system SET $column = ? WHERE id = ? AND $column IS NULL"
    logger.trace(s"UPDATE SQL: $sql, signing key")
    runner.update(RequestContext.getConn, sql, key, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID) == 1

  // overriding the base method since there is no repository relation in this model
  override def getById(id: String): SystemMetadata =
    val sql: String = """
      SELECT *
        FROM system
       WHERE id = ?
     """
    getOneBySql(sql, List(id.toInt))
