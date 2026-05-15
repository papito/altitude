package altitude.core.dao.postgres

import altitude.core.dao.jdbc.BaseDao

import java.time.LocalDateTime

trait PostgresOverrides { this: BaseDao[?] =>
  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def nativeBool(value: Boolean): Any = {
    if value then true else false
  }

  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] = {
    if value.isEmpty || value.get == null then {
      return None
    }

    val timeStamp = value.get.asInstanceOf[java.sql.Timestamp]
    Some(timeStamp.toLocalDateTime)
  }

  def count(recs: List[Map[String, AnyRef]]): Int = if recs.nonEmpty then recs.head("total").asInstanceOf[Long].toInt else 0

  override protected def getBooleanField(value: AnyRef): Boolean = value.asInstanceOf[Boolean]

  override protected def getNextVal(tableName: String): AnyRef = {
    val labelSql = f"SELECT nextval('$tableName')"
    val labelRes = executeAndGetOne(labelSql, List())
    labelRes("nextval")
  }

  override val forUpdate: String = "FOR UPDATE"
}
