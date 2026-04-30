package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.SystemMetadata
import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner

abstract class SystemMetadataDao(override val config: Config) extends BaseDao[SystemMetadata] with altitude.core.dao.SystemMetadataDao {

  override val tableName = "system"

  override protected def makeModel(rec: Map[String, AnyRef]): SystemMetadata = SystemMetadata(
    version = rec(FieldConst.SystemMetadata.VERSION).asInstanceOf[Int],
    isInitialized = getBooleanField(rec(FieldConst.SystemMetadata.IS_INITIALIZED))
  )

  def updateVersion(toVersion: Int): Unit = {
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.VERSION} = ? WHERE id = ?"
    runner.update(RequestContext.getConn, sql, toVersion, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)
  }

  def setInitialized(): Unit = {
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"
    runner.update(RequestContext.getConn, sql, true, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)
  }

  def setUninitialized(): Unit = {
    val runner: QueryRunner = new QueryRunner()
    val sql = s"UPDATE system SET ${FieldConst.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"
    runner.update(RequestContext.getConn, sql, false, altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)
  }

  // overriding the base method since there is no repository relation in this model
  override def getById(id: String): SystemMetadata = {
    val sql: String = """
      SELECT *
        FROM system
       WHERE id = ?
     """
    getOneBySql(sql, List(id.toInt))
  }
}
