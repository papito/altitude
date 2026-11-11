package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/**
 * The `account` table, behind the `User` model. `passwordHash` is never part of the model and is read on its own. `emailKey` and
 * `usernameKey` are the name keys `account_01` and `account_02` keep unique, written by `UserDao.addUser` and compared by a
 * login; the model derives them from the email address and the username.
 */
case class AccountRow[T[_]](
    id: T[String],
    email: T[String],
    emailKey: T[String],
    username: T[String],
    usernameKey: T[String],
    name: T[String],
    accountType: T[String],
    passwordHash: T[String],
    isActive: T[Boolean],
    canCreateLibraries: T[Boolean],
    grantsNewLibraries: T[Boolean],
    newLibraryPermissions: T[Int],
    createdByAccountId: T[Option[String]],
    lastLoginAt: T[Option[OffsetDateTime]],
    lastActiveRepoId: T[Option[String]],
    createdAt: T[Option[OffsetDateTime]],
    updatedAt: T[Option[OffsetDateTime]])

object AccountRow extends Table[AccountRow]:
  override def tableName: String = "account"
