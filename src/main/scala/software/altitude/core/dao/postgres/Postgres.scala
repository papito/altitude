package software.altitude.core.dao.postgres

import org.joda.time.DateTime
import software.altitude.core.dao.jdbc.BaseJdbcDao
import software.altitude.core.models.BaseModel
import software.altitude.core.{Const => C}

import java.sql.Timestamp


trait Postgres { this: BaseJdbcDao =>

  override protected def defaultSqlColsForSelect: List[String] = List(
    C.Base.ID,
    "*",
    "EXTRACT(EPOCH FROM created_at) AS created_at",
    "EXTRACT(EPOCH FROM updated_at) AS updated_at"
  )

  override protected def nowTimeFunc = "current_timestamp"

  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def addCoreAttrs(model: BaseModel, rec: Map[String, AnyRef]): model.type = {
    val createdAtMilis = rec.getOrElse(C.Base.CREATED_AT, 0d).asInstanceOf[Double].toLong
    if (createdAtMilis != 0d) {
      model.createdAt = new DateTime(createdAtMilis * 1000)
    }

    val updatedAtMilis = rec.getOrElse(C.Base.UPDATED_AT, 0d).asInstanceOf[Double].toLong
    if (updatedAtMilis != 0d) {
      model.updatedAt = new DateTime(createdAtMilis * 1000)
    }

    model
  }

  override protected def getDateTimeFromRec(field: String, rec: Map[String, AnyRef]): Option[DateTime] = {
    val timestamp: Timestamp = rec(field).asInstanceOf[Timestamp]
    val dt = new DateTime(timestamp.getTime).withMillisOfSecond(0)
    Some(dt)
  }

  override protected def dateTimeToDbFunc(datetime: Option[DateTime]): String = {
    datetime match {
      case None => null
      case _ => s"to_timestamp(${datetime.get.getMillis} / 1000)"
    }
  }

}

