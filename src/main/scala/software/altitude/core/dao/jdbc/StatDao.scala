package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json.JsObject
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models.Stat

abstract class StatDao(override val config: Config) extends BaseDao with software.altitude.core.dao.StatDao {

  override final val tableName = "stats"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = Stat(
    rec(FieldConst.Stat.DIMENSION).asInstanceOf[String],
    rec(FieldConst.Stat.DIM_VAL).asInstanceOf[Int])

  override def add(jsonIn: JsObject): JsObject = {
    val sql: String = s"""
      INSERT INTO $tableName (${FieldConst.REPO_ID}, ${FieldConst.Stat.DIMENSION})
           VALUES (? ,?)"""

    val stat: Stat = jsonIn
    val values: List[Any] = RequestContext.getRepository.persistedId :: stat.dimension :: Nil

    addRecord(jsonIn, sql, values)
    jsonIn
  }

  override protected def addRecord(jsonIn: JsObject, q: String, values: List[Any]): Unit = {
    logger.info(s"JDBC INSERT: $jsonIn")

    BaseDao.incrWriteQueryCount()

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, q, values.map(_.asInstanceOf[Object]): _*)
  }

  /**
   * Increment a particular stat name, per repository
   * @param statName the name of the stat
   * @param count the value to increment by - CAN be negative
   */
  def incrementStat(statName: String, count: Long = 1): Unit = {
    BaseDao.incrWriteQueryCount()

    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.Stat.DIM_VAL} = ${FieldConst.Stat.DIM_VAL} + $count
       WHERE ${FieldConst.REPO_ID} = ? and ${FieldConst.Stat.DIMENSION} = ?
      """
    logger.debug(s"INCR STAT SQL: $sql, for $statName")

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, sql, RequestContext.getRepository.persistedId, statName)
  }
}
