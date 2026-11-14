package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `watch_folder_file` table */
case class WatchFolderFileRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    watchFolderId: T[String],
    relPath: T[String],
    sizeBytes: T[Long],
    modifiedAt: T[Long],
    state: T[String],
    attempts: T[Int],
    lastSeenAt: T[OffsetDateTime],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object WatchFolderFileRow extends Table[WatchFolderFileRow]:
  override def tableName: String = "watch_folder_file"
