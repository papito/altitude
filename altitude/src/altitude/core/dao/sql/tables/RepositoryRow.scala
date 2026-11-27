package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `repository` table. `fileStoreConfig` is `jsonb` on PostgreSQL and `TEXT` on SQLite; pgJDBC hands `jsonb` out as its JSON
 * text, so both read as a string. `nameKey` is the key `repository_01` keeps unique, written but never read back: the model
 * derives it from the name.
 */
case class RepositoryRow[T[_]](
    id: T[String],
    name: T[String],
    nameKey: T[String],
    description: T[Option[String]],
    rootFolderId: T[String],
    fileStoreType: T[String],
    fileStoreConfig: T[String],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object RepositoryRow extends Table[RepositoryRow]:
  override def tableName: String = "repository"
