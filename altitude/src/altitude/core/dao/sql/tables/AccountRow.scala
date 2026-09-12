package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `account` table, behind the `User` model. `passwordHash` is never part of the model and is read on its own. */
case class AccountRow[T[_]](
    id: T[String],
    email: T[String],
    name: T[String],
    accountType: T[String],
    passwordHash: T[String],
    lastActiveRepoId: T[Option[String]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AccountRow extends Table[AccountRow]:
  override def tableName: String = "account"
