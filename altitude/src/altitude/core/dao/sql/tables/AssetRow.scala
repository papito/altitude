package altitude.core.dao.sql.tables

import java.time.LocalDateTime
import java.time.OffsetDateTime
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.Db

/**
 * What a select of the asset's columns does with the two bulky metadata JSON columns, `user_metadata` and `extracted_metadata`
 * ([[AssetRow.columnList]])
 */
enum BulkyMetadata:
  /** Selected as stored */
  case Kept

  /** Left out of the list, for a hand-written read, whose mapper reads an absent column as empty (`AssetDao.makeModel`) */
  case Omitted

  /** Read as NULL in their places, for a typed read, whose row reads back by position: its model's two are empty */
  case AsNull

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
 * The Scene embeddings (`dinov2_embedding`, `siglip2_embedding`) are never carried back out of the database by a typed read, so
 * the row class does not even select them: they are bulky, and the one reader is the hand-written Sequence candidates' statement.
 * The statements that name the asset's columns build their list from these labels ([[AssetRow.columnList]]), so they leave them
 * out too.
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

  private val BULKY_METADATA = Set(FieldConst.Asset.USER_METADATA, FieldConst.Asset.EXTRACTED_METADATA)

  /**
   * The asset's columns as a hand-written select lists them: this row's labels in the order it declares them, each as
   * `asset.<column>`, so the typed row stays the one list of what a read of an asset selects. `bulkyMetadata` is what becomes of
   * the two bulky metadata JSON columns, for the reads that show neither. `public_metadata` stays in every form: a cell shows it
   * and the Sequence rule compares the device model.
   */
  def columnList(bulkyMetadata: BulkyMetadata = BulkyMetadata.Kept): String =
    Table
      .labels(this)
      .map(Db.config.columnNameMapper)
      .flatMap {
        name =>
          if !BULKY_METADATA(name) then Some(s"asset.$name")
          else
            bulkyMetadata match
              case BulkyMetadata.Kept => Some(s"asset.$name")
              case BulkyMetadata.Omitted => None
              case BulkyMetadata.AsNull => Some(s"NULL AS $name")
      }
      .mkString(", ")
