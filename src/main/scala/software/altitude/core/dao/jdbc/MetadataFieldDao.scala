package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models.FieldType
import software.altitude.core.models.UserMetadataField

abstract class MetadataFieldDao(override val config: Config)
  extends BaseDao
  with software.altitude.core.dao.UserMetadataFieldDao {

  final override val tableName = "metadata_field"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    UserMetadataField(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.MetadataField.NAME).asInstanceOf[String],
      fieldType = FieldType.withName(rec(FieldConst.MetadataField.FIELD_TYPE).asInstanceOf[String])
    )
  }

  override def add(jsonIn: JsObject): JsObject = {
    val metadataField = jsonIn: UserMetadataField

    val sql = s"""
        INSERT INTO $tableName (
             ${FieldConst.ID},
             ${FieldConst.REPO_ID},
             ${FieldConst.MetadataField.NAME},
             ${FieldConst.MetadataField.NAME_LC},
             ${FieldConst.MetadataField.FIELD_TYPE})
            VALUES (?, ?, ?, ?, ?)
        """

    val id = BaseDao.genId

    val sqlVals: List[Any] =
      List(
        id,
        RequestContext.getRepository.persistedId,
        metadataField.name,
        metadataField.nameLowercase,
        metadataField.fieldType.toString)

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(FieldConst.ID -> id)
  }
}
