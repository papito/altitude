package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `metadata_parameter` table: one row per indexed user-metadata value, with the value living in the column of its type.
 *
 * A search only ever reads `asset_id` out of it, through a semi-join; the value columns are compared against, never selected.
 */
case class MetadataParameterRow[T[_]](
    repositoryId: T[String],
    assetId: T[String],
    fieldId: T[String],
    fieldValueKw: T[Option[String]],
    fieldValueNum: T[Option[BigDecimal]],
    fieldValueBool: T[Option[Boolean]],
    fieldValueDt: T[Option[OffsetDateTime]])

object MetadataParameterRow extends Table[MetadataParameterRow]:
  override def tableName: String = "metadata_parameter"
