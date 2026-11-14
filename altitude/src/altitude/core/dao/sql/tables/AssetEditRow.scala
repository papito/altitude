package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `asset_edit` table: an asset's Edit, its head and its renditions' revisions; its steps are `asset_edit_step`'s */
case class AssetEditRow[T[_]](
    repositoryId: T[String],
    assetId: T[String],
    headSeq: T[Int],
    renderedSeq: T[Option[Int]],
    lastRev: T[Int],
    isEdited: T[Boolean],
    width: T[Option[Int]],
    height: T[Option[Int]],
    updatedByAccountId: T[Option[String]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AssetEditRow extends Table[AssetEditRow]:
  override def tableName: String = "asset_edit"
