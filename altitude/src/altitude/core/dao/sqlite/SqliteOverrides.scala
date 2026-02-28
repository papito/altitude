package altitude.core.dao.sqlite

import altitude.core.dao.jdbc.BaseDao

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId

trait SqliteOverrides { this: BaseDao =>

  override protected def jsonFunc = "?"

  override protected def nativeBool(value: Boolean): Any = {
    if (value) "1" else "0"
  }

  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] = {
    if (value.isEmpty || value.get == null) {
      return None
    }

    val timeStamp = value.get.asInstanceOf[String]
    Some(stringToLocalDateTime(timeStamp))
  }

  private def stringToLocalDateTime(datetimeAsString: String): java.time.LocalDateTime = {
    val formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val date = formatter.parse(datetimeAsString)
    date.toInstant.atZone(ZoneId.systemDefault).toLocalDateTime
  }

  def count(recs: List[Map[String, AnyRef]]): Int = if (recs.nonEmpty) recs.head("total").asInstanceOf[Int] else 0

  // SQLITE does not have a BOOLEAN type, so we use an INTEGER type instead and "fix it in post"
  override protected def getBooleanField(value: AnyRef): Boolean = value match {
    case b: java.lang.Boolean => b.booleanValue
    case i: java.lang.Integer =>
      i.intValue match {
        case 0 => false
        case 1 => true
        case _ => false
      }
    case _ => false
  }

  override  protected def getNextVal(tableName: String): AnyRef = {
    val sql = s"INSERT INTO $tableName DEFAULT VALUES RETURNING id"
    val res = executeAndGetOne(sql, List())
    res("id")
  }

  override val forUpdate: String = "" // SQLITE does not support row-level locking, so no need for "FOR UPDATE"
}
