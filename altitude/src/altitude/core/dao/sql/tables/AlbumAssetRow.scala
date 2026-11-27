package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `album_asset` link table. It has no model: memberships are written by hand-written SQL and read only as a search filter. */
case class AlbumAssetRow[T[_]](
    repositoryId: T[String],
    albumId: T[String],
    assetId: T[String],
    createdAt: T[Option[OffsetDateTime]])

object AlbumAssetRow extends Table[AlbumAssetRow]:
  override def tableName: String = "album_asset"
