package software.altitude.core.dao.sqlite

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import software.altitude.core.dao.jdbc.BaseJdbcDao
import software.altitude.core.models.BaseModel
import software.altitude.core.{Const => C}

trait Sqlite { this: BaseJdbcDao =>

  override protected def defaultSqlColsForSelect: List[String] = List(
    C.Base.ID,
    "*",
    "CAST(STRFTIME('%s', created_at) AS INT) AS created_at",
    "CAST(STRFTIME('%s', updated_at) AS INT) AS updated_at"
  )

  override protected def nowTimeFunc = "datetime('now', 'localtime')"

  override protected def jsonFunc = "?"

  override protected def addCoreAttrs(model: BaseModel, rec: Map[String, AnyRef]): model.type = {
    val createdAtSeconds = rec.getOrElse(C.Base.CREATED_AT, 0).asInstanceOf[Int]
    if (createdAtSeconds != 0) {
      model.createdAt = new DateTime(createdAtSeconds.toLong * 1000)
    }

    val updatedAtSeconds = rec.getOrElse(C.Base.UPDATED_AT, 0).asInstanceOf[Int]
    if (updatedAtSeconds != 0) {
      model.updatedAt = new DateTime(updatedAtSeconds.toLong * 1000)
    }

    model
  }

  override protected def getDateTimeFromRec(field: String, rec: Map[String, AnyRef]): Option[DateTime] = {
    val timestamp: String = rec(field).asInstanceOf[String]
    val formatter: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
    val dt: DateTime = formatter.withZone(DateTimeZone.forID("UTC")).parseDateTime(timestamp)
    Some(dt)
  }

  override protected def dateTimeToDbFunc(datetime: Option[DateTime]): String = {
    datetime match {
      case None => null
      case _ => s"strftime('%Y-%m-%d %H:%M:%S Z', ${datetime.get.getMillis} / 1000, 'unixepoch')"
    }
  }

}
