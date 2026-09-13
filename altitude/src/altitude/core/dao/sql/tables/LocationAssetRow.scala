package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `location_asset` link table. Like [[AlbumAssetRow]]: no model, no `updated_at`, read only as a search filter. */
case class LocationAssetRow[T[_]](
    repositoryId: T[String],
    locationId: T[String],
    assetId: T[String],
    createdAt: T[Option[OffsetDateTime]])

object LocationAssetRow extends Table[LocationAssetRow]:
  override def tableName: String = "location_asset"
