package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.FieldType
import altitude.core.models.UserMetadataField
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import com.typesafe.config.Config

import scala.language.implicitConversions

abstract class MetadataFieldDao(override val config: Config) extends BaseDao with altitude.core.dao.UserMetadataFieldDao:

  final override val tableName = "metadata_field"

  override protected def makeModel(rec: Map[String, AnyRef]): ujson.Obj =
    UserMetadataField(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.MetadataField.NAME).asInstanceOf[String],
      fieldType = FieldType.valueOf(rec(FieldConst.MetadataField.FIELD_TYPE).asInstanceOf[String])
    ).toJson

  override def add(jsonIn: ujson.Obj): ujson.Obj =
    val metadataField: UserMetadataField = jsonIn

    val sql = s"""
        INSERT INTO metadata_field (
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

    jsonIn(FieldConst.ID) = id
    jsonIn
