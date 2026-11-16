package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

case class AlbumRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameKey: T[String],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AlbumRow extends Table[AlbumRow]:
  override def tableName: String = "album"
