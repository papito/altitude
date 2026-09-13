package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `folder` table */
case class FolderRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameLc: T[String],
    parentId: T[String],
    isRecycled: T[Boolean],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object FolderRow extends Table[FolderRow]:
  override def tableName: String = "folder"
