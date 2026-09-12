package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.MetadataFieldRow
import altitude.core.models.FieldType
import altitude.core.models.UserMetadataField

abstract class MetadataFieldDao(override val config: Config)
  extends BaseDao[UserMetadataField]
  with altitude.core.dao.UserMetadataFieldDao:

  final override val tableName = "metadata_field"

  final override type Row[T[_]] = MetadataFieldRow[T]
  final override protected def table: Table[Row] = MetadataFieldRow

  override protected def toModel(row: MetadataFieldRow[Sc]): UserMetadataField =
    UserMetadataField(id = Option(row.id), name = row.name, fieldType = FieldType.valueOf(row.fieldType))

  override protected def makeModel(rec: Map[String, AnyRef]): UserMetadataField =
    UserMetadataField(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.MetadataField.NAME).asInstanceOf[String],
      fieldType = FieldType.valueOf(rec(FieldConst.MetadataField.FIELD_TYPE).asInstanceOf[String])
    )

  override def add(metadataField: UserMetadataField): UserMetadataField =
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

    addRecord(sql, sqlVals)
    metadataField.copy(id = Some(id))
