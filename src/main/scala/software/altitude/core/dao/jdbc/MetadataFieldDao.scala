package software.altitude.core.dao.jdbc

import play.api.libs.json.JsObject
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

abstract class MetadataFieldDao(val app: AltitudeCoreApp)
  extends BaseJdbcDao with software.altitude.core.dao.MetadataFieldDao {

  override final val tableName = "metadata_field"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = MetadataField(
      id = Some(rec(C.Base.ID).asInstanceOf[String]),
      name = rec(C.MetadataField.NAME).asInstanceOf[String],
      fieldType = FieldType.withName(
        rec(C.MetadataField.FIELD_TYPE).asInstanceOf[String])
    )
    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject)(implicit ctx: Context, txId: TransactionId): JsObject = {
    val metadataField = jsonIn: MetadataField

    val sql = s"""
        INSERT INTO $tableName (
             ${coreSqlColsForInsert.mkString(", ")},
             ${C.MetadataField.NAME},
             ${C.MetadataField.NAME_LC},
             ${C.MetadataField.FIELD_TYPE})
            VALUES ($coreSqlValsForInsert, ?, ?, ?)
        """

    val sqlVals: List[Any] = List(
      metadataField.name,
      metadataField.nameLowercase,
      metadataField.fieldType.toString)

    addRecord(jsonIn, sql, sqlVals)
  }
}
