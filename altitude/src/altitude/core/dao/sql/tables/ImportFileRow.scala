package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `import_file` table */
case class ImportFileRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    importId: T[String],
    relPath: T[String],
    outcome: T[String],
    reason: T[Option[String]],
    assetId: T[Option[String]],
    sizeBytes: T[Long],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object ImportFileRow extends Table[ImportFileRow]:
  override def tableName: String = "import_file"
