package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `import` table. The five counters are named by the outcome they count. */
case class ImportRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    source: T[String],
    name: T[Option[String]],
    watchFolderId: T[Option[String]],
    accountId: T[Option[String]],
    destFolderId: T[Option[String]],
    expectedFiles: T[Option[Int]],
    isComplete: T[Boolean],
    closedAt: T[Option[OffsetDateTime]],
    pending: T[Int],
    imported: T[Int],
    duplicate: T[Int],
    unsupported: T[Int],
    failed: T[Int],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object ImportRow extends Table[ImportRow]:
  override def tableName: String = "import"
