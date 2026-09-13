package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `album` table. Memberships live in `album_asset`, which has no model and is written only through hand-written SQL. */
case class AlbumRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameLc: T[String],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AlbumRow extends Table[AlbumRow]:
  override def tableName: String = "album"
