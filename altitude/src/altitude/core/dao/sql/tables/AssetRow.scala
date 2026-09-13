package altitude.core.dao.sql.tables

import java.time.LocalDateTime
import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `asset` table.
 *
 * `originalCreatedAt` is the camera's wall clock with no zone; `createdAt` / `updatedAt` are instants. The two are different
 * kinds of value and are typed differently here so neither engine's mapper can confuse them.
 *
 * `folderId` is nullable in the schema but every row the application writes has one, and the model trims it - reading it as a
 * plain `String` keeps the raw path's behaviour exactly.
 */
case class AssetRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    userId: T[String],
    checksum: T[Int],
    mediaType: T[String],
    mediaSubtype: T[String],
    mimeType: T[String],
    width: T[Int],
    height: T[Int],
    areaSize: T[Int],
    userMetadata: T[Option[String]],
    publicMetadata: T[Option[String]],
    extractedMetadata: T[Option[String]],
    folderId: T[String],
    filename: T[String],
    sizeBytes: T[Int],
    isTriaged: T[Boolean],
    isRecycled: T[Boolean],
    isPurged: T[Boolean],
    isPipelineProcessed: T[Boolean],
    originalCreatedAt: T[Option[LocalDateTime]],
    originalCreatedAtSource: T[Option[String]],
    latitude: T[Option[Double]],
    longitude: T[Option[Double]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AssetRow extends Table[AssetRow]:
  override def tableName: String = "asset"
