package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `watch_folder` table */
case class WatchFolderRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    path: T[String],
    destFolderId: T[Option[String]],
    deletesImported: T[Boolean],
    isPaused: T[Boolean],
    accountId: T[String],
    statusReason: T[Option[String]],
    lastScanAt: T[Option[OffsetDateTime]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object WatchFolderRow extends Table[WatchFolderRow]:
  override def tableName: String = "watch_folder"
