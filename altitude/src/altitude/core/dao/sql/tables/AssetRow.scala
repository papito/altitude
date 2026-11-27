package altitude.core.dao.sql.tables

import java.time.LocalDateTime
import java.time.OffsetDateTime
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.Db

/** What [[AssetRow.columnList]] does with the two bulky metadata JSON columns, `user_metadata` and `extracted_metadata` */
enum BulkyMetadata:
  case Kept

  /** Left out, for a hand-written read, whose mapper reads an absent column as empty (`AssetDao.makeModel`) */
  case Omitted

  /** Selected as NULL, for a typed read, which reads its row back by position */
  case AsNull

/**
 * The `asset` table.
 *
 * `originalCreatedAt` is the camera's zoneless wall clock while `createdAt` / `updatedAt` are instants; they are typed
 * differently so neither engine's mapper can confuse them. `folderId` is NULL for a triaged asset, whose model has an empty
 * folder ID. `originalOwner` is the importer record's JSON text (`jsonb` on PostgreSQL, `TEXT` on SQLite). `filename_sort`, the
 * file name's `SortKey`, is not in the row, so no read of a row carries it: the file name sort names it beside `filename`.
 *
 * The bulky Scene embeddings (`dinov2_embedding`, `siglip2_embedding`) are not in this row, so [[AssetRow.columnList]] leaves
 * them out too; only the hand-written Sequence candidates statements select them.
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
    areaSize: T[Long],
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
   * This row's columns as `asset.<column>` for a hand-written select, so the typed row stays the one list of what an asset read
   * selects. `public_metadata` stays in every form: a cell shows it and the Sequence rule compares the device model.
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
