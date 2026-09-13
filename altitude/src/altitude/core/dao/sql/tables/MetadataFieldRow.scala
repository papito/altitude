package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `metadata_field` table, behind the `UserMetadataField` model */
case class MetadataFieldRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameLc: T[String],
    fieldType: T[String],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object MetadataFieldRow extends Table[MetadataFieldRow]:
  override def tableName: String = "metadata_field"
