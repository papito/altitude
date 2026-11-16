package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `asset_edit` table. `recipe` is the Edit's JSON text: `jsonb` on PostgreSQL, `TEXT` on SQLite. */
case class AssetEditRow[T[_]](
    repositoryId: T[String],
    assetId: T[String],
    recipe: T[String],
    editVersion: T[Int],
    renderedVersion: T[Option[Int]],
    lastRev: T[Int],
    isEdited: T[Boolean],
    width: T[Int],
    height: T[Int],
    updatedByAccountId: T[Option[String]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AssetEditRow extends Table[AssetEditRow]:
  override def tableName: String = "asset_edit"
