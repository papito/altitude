package software.altitude.core.dao.postgres
import java.time.LocalDateTime

import software.altitude.core.dao.jdbc.BaseDao

trait PostgresOverrides { this: BaseDao =>
  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] = {
    if (value.isEmpty || value.get == null) {
      return None
    }

    val timeStamp = value.get.asInstanceOf[java.sql.Timestamp]
    Some(timeStamp.toLocalDateTime)
  }

  def count(recs: List[Map[String, AnyRef]]): Int = if (recs.nonEmpty) recs.head("total").asInstanceOf[Long].toInt else 0

  override protected def getBooleanField(value: AnyRef): Boolean = value.asInstanceOf[Boolean]

}
