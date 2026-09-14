package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `location` table: a category (a named container, no pin) or a Location (a pin) - `kind` says which. Memberships live in
 * `location_asset`.
 */
case class LocationRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    categoryId: T[Option[String]],
    kind: T[String],
    name: T[String],
    nameLc: T[String],
    latitude: T[Option[Double]],
    longitude: T[Option[Double]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object LocationRow extends Table[LocationRow]:
  override def tableName: String = "location"
