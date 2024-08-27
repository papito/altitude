package software.altitude.core.dao.sqlite

import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.BaseModel
import software.altitude.core.models.Field

import java.text.SimpleDateFormat
import java.time.ZoneId

trait SqliteOverrides { this: BaseDao =>

  override protected def jsonFunc = "?"

  override protected def addCoreAttrs(model: BaseModel, rec: Map[String, AnyRef]): model.type = {
    if (rec(Field.CREATED_AT) != null) {
      val datetimeAsString = rec(Field.CREATED_AT).asInstanceOf[String]
      model.createdAt = stringToLocalDateTime(datetimeAsString)
    }

    if (rec(Field.UPDATED_AT) != null) {
      val datetimeAsString = rec(Field.UPDATED_AT).asInstanceOf[String]
      model.updatedAt = stringToLocalDateTime(datetimeAsString)
    }

    model
  }

  private def stringToLocalDateTime(datetimeAsString: String): java.time.LocalDateTime = {
    val formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val date = formatter.parse(datetimeAsString)
    date.toInstant.atZone(ZoneId.systemDefault).toLocalDateTime
  }

  def count(recs: List[Map[String, AnyRef]]): Int = if (recs.nonEmpty) recs.head("total").asInstanceOf[Int] else 0

  // SQLITE does not have a BOOLEAN type, so we use an INTEGER type instead and "fix it in post"
  override protected def getBooleanField(value: AnyRef): Boolean = value.asInstanceOf[Int] match {
    case 0 => false
    case 1 => true
  }
}
