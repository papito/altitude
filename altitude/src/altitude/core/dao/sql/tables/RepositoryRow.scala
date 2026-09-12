package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `repository` table. `fileStoreConfig` is `jsonb` on PostgreSQL and `TEXT` on SQLite; pgJDBC hands a `jsonb` column out as
 * its JSON text, which is what the model parses, so both read as a plain string.
 */
case class RepositoryRow[T[_]](
    id: T[String],
    name: T[String],
    description: T[Option[String]],
    ownerAccountId: T[String],
    rootFolderId: T[String],
    fileStoreType: T[String],
    fileStoreConfig: T[Option[String]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object RepositoryRow extends Table[RepositoryRow]:
  override def tableName: String = "repository"
