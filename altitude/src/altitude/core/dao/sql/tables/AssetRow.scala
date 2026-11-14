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
 * `folderId` is NULL for an asset in no folder (a triaged one), whose model has an empty folder ID, as the raw path reads it.
 *
 * `originalOwner` is the importer record's JSON: `jsonb` on PostgreSQL, which pgJDBC hands out as its JSON text, and `TEXT` on
 * SQLite.
 *
 * The Scene embeddings (`dinov2_embedding`, `clip_embedding`) are never carried back out of the database by a typed read, so the
 * row class does not even select them: they are bulky, and the one reader is the hand-written Sequence candidates' statement. The
 * search statements build their asset column list from these labels, so they leave them out too.
 */
case class AssetRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    ownerUserId: T[String],
    originalOwner: T[String],
    checksum: T[String],
    mediaType: T[String],
    mediaSubtype: T[String],
    mimeType: T[String],
    width: T[Int],
    height: T[Int],
    areaSize: T[Int],
    userMetadata: T[Option[String]],
    publicMetadata: T[Option[String]],
    extractedMetadata: T[Option[String]],
    folderId: T[Option[String]],
    filename: T[String],
    sizeBytes: T[Long],
    isTriaged: T[Boolean],
    isRecycled: T[Boolean],
    isPurged: T[Boolean],
    isPipelineProcessed: T[Boolean],
    originalCreatedAt: T[Option[LocalDateTime]],
    originalCreatedAtSource: T[Option[String]],
    latitude: T[Option[Double]],
    longitude: T[Option[Double]],
    durationMs: T[Option[Long]],
    sharpness: T[Option[Double]],
    luminance: T[Option[Double]],
    clippedHighlights: T[Option[Double]],
    crushedShadows: T[Option[Double]],
    imageQuality: T[Option[Int]],
    perceptualHash: T[Option[Long]],
    sequenceId: T[Option[String]],
    editRev: T[Int],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AssetRow extends Table[AssetRow]:
  override def tableName: String = "asset"
