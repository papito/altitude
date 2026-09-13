package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `location` table: a parent (a named container, no pin) or a Location (a pin with an optional radius) - `kind` says which.
 * Memberships live in `location_asset`.
 */
case class LocationRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    parentId: T[Option[String]],
    kind: T[String],
    name: T[String],
    nameLc: T[String],
    latitude: T[Option[Double]],
    longitude: T[Option[Double]],
    radiusM: T[Option[Int]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object LocationRow extends Table[LocationRow]:
  override def tableName: String = "location"
