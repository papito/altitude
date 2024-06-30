package software.altitude.core.dao.jdbc

import org.apache.commons.dbutils.QueryRunner
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.AltitudeAppContext
import software.altitude.core.RequestContext
import software.altitude.core.models.Stat
import software.altitude.core.{Const => C}

abstract class StatDao(val appContext: AltitudeAppContext) extends BaseDao with software.altitude.core.dao.StatDao {
  private final val log = LoggerFactory.getLogger(getClass)

  override final val tableName = "stats"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = Stat(
    rec(C.Stat.DIMENSION).asInstanceOf[String],
    rec(C.Stat.DIM_VAL).asInstanceOf[Int])

  override def add(jsonIn: JsObject): JsObject = {
    val sql: String = s"""
      INSERT INTO $tableName (${C.Base.REPO_ID}, ${C.Stat.DIMENSION})
           VALUES (? ,?)"""

    val stat: Stat = jsonIn
    val values: List[Any] = RequestContext.getRepository.persistedId :: stat.dimension :: Nil

    addRecord(jsonIn, sql, values)
    jsonIn
  }

  override protected def addRecord(jsonIn: JsObject, q: String, values: List[Any]): Unit = {
    log.info(s"JDBC INSERT: $jsonIn")
    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, q, values.map(_.asInstanceOf[Object]): _*)
  }

  /**
   * Increment a particular stat name, per repository
   * @param statName the name of the stat
   * @param count the value to increment by - CAN be negative
   */
  def incrementStat(statName: String, count: Long = 1): Unit = {
    val sql = s"""
      UPDATE $tableName
         SET ${C.Stat.DIM_VAL} = ${C.Stat.DIM_VAL} + $count
       WHERE ${C.Base.REPO_ID} = ? and ${C.Stat.DIMENSION} = ?
      """
    log.debug(s"INCR STAT SQL: $sql, for $statName")

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, sql, RequestContext.getRepository.persistedId, statName)
  }
}
