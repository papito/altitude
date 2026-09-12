package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `person` table */
case class PersonRow[T[_]](
    id: T[String],
    repositoryId: T[String],
    name: T[String],
    nameForSort: T[String],
    coverFaceId: T[Option[String]],
    numOfFaces: T[Int],
    isNamed: T[Boolean],
    isHidden: T[Boolean],
    isDeleted: T[Boolean],
    isBadMatch: T[Boolean],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object PersonRow extends Table[PersonRow]:
  override def tableName: String = "person"
