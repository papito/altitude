package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `face` table without `features`: the model never carries the vector, and the column's type differs by engine (pgvector
 * `vector`, SQLite `BLOB`). Face vector search uses hand-written engine SQL.
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
    checksum: T[String],
    quality: T[Double],
    isEnrolled: T[Boolean],
    frameTimeMs: T[Option[Long]],
    sharpness: T[Option[Double]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object FaceRow extends Table[FaceRow]:
  override def tableName: String = "face"
