package software.altitude.core.dao.postgres
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.BaseModel
import software.altitude.core.{Const => C}


trait PostgresOverrides { this: BaseDao =>
  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def addCoreAttrs(model: BaseModel, rec: Map[String, AnyRef]): model.type = {
    if (rec(C.Base.CREATED_AT) != null) {
      val createdAtTimestamp = rec(C.Base.CREATED_AT).asInstanceOf[java.sql.Timestamp]
      model.createdAt = createdAtTimestamp.toLocalDateTime
    }

    if (rec(C.Base.UPDATED_AT) != null) {
      val updatedAtTimestamp = rec(C.Base.UPDATED_AT).asInstanceOf[java.sql.Timestamp]
      model.updatedAt = updatedAtTimestamp.toLocalDateTime
    }

    model
  }

  def count(recs: List[Map[String, AnyRef]]): Int = if (recs.nonEmpty) recs.head("total").asInstanceOf[Long].toInt else 0

  override protected def getBooleanField(value: AnyRef): Boolean = value.asInstanceOf[Boolean]

}
