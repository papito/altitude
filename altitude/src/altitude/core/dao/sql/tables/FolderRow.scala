package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

case class FolderRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameKey: T[String],
    parentId: T[String],
    isRecycled: T[Boolean],
    createdAt: T[Option[OffsetDateTime]])

object FolderRow extends Table[FolderRow]:
  override def tableName: String = "folder"
