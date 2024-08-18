package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.RequestContext
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.{Const => C}

abstract class MetadataFieldDao(override val config: Config)
  extends BaseDao with software.altitude.core.dao.MetadataFieldDao {

  override final val tableName = "metadata_field"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = MetadataField(
      id = Option(rec(C.Base.ID).asInstanceOf[String]),
      name = rec(C.MetadataField.NAME).asInstanceOf[String],
      fieldType = FieldType.withName(
        rec(C.MetadataField.FIELD_TYPE).asInstanceOf[String])
    )
    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val metadataField = jsonIn: MetadataField

    val sql = s"""
        INSERT INTO $tableName (
             ${C.MetadataField.ID},
             ${C.MetadataField.REPO_ID},
             ${C.MetadataField.NAME},
             ${C.MetadataField.NAME_LC},
             ${C.MetadataField.FIELD_TYPE})
            VALUES (?, ?, ?, ?, ?)
        """

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      metadataField.name,
      metadataField.nameLowercase,
      metadataField.fieldType.toString)

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(C.Base.ID -> id)
  }
}
