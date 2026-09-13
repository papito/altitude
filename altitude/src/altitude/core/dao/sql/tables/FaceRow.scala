package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `face` table, without `features`: the model never carries the vector back out of the database, and the column is a pgvector
 * `vector` on PostgreSQL against a `BLOB` on SQLite. Face vector search stays on its hand-written engine SQL.
 */
case class FaceRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    assetId: T[String],
    personId: T[String],
    x1: T[Int],
    y1: T[Int],
    width: T[Int],
    height: T[Int],
    detectionScore: T[Double],
    checksum: T[Int],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object FaceRow extends Table[FaceRow]:
  override def tableName: String = "face"
