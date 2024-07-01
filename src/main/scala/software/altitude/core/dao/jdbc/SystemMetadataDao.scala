package software.altitude.core.dao.jdbc

import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json.JsObject
import software.altitude.core.Configuration
import software.altitude.core.RequestContext
import software.altitude.core.models.SystemMetadata
import software.altitude.core.{Const => C}

abstract class SystemMetadataDao(override val config: Configuration)
  extends BaseDao with software.altitude.core.dao.SystemMetadataDao {

  override val tableName = "system"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = SystemMetadata(
    version = rec(C.SystemMetadata.VERSION).asInstanceOf[Int],
    isInitialized = getBooleanField(rec(C.SystemMetadata.IS_INITIALIZED))
  ).toJson

  def updateVersion(toVersion: Int): Unit = {
    val runner: QueryRunner = new QueryRunner()

    val sql = s"UPDATE $tableName SET ${C.SystemMetadata.VERSION} = ? WHERE id = ?"

    runner.update(
      RequestContext.getConn,
      sql, toVersion, software.altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)
  }

  def setInitialized(): Unit = {
    val runner: QueryRunner = new QueryRunner()

    val sql = s"UPDATE $tableName SET ${C.SystemMetadata.IS_INITIALIZED} = ? WHERE id = ?"

    runner.update(
      RequestContext.getConn,
      sql, true, software.altitude.core.dao.SystemMetadataDao.SYSTEM_RECORD_ID)
  }

  // overriding the base method since there is no repository relation in this model
  override def getById(id: String): JsObject = {
    val sql: String = s"""
      SELECT *
        FROM $tableName
       WHERE id = ?
     """

    getOneBySql(sql, List(id.toInt))
  }
}
