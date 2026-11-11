package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.NameKey

object User:
  given JsonCodec.ReadWriter[User] = JsonCodec.macroRW
  given Conversion[ujson.Value, User] = json => JsonCodec.read[User](json)

/**
 * An account (`CONTEXT.md`). The email address and the username each name one account under their name keys ([[emailKey]] and
 * [[usernameKey]], `NameKey.of`), which `account_01` and `account_02` keep unique and a login compares. `canCreateLibraries` is
 * the Create Libraries flag; `grantsNewLibraries` and `newLibraryPermissions` are the new-Library grant: with the switch on, a
 * Library created later grants the account those Permissions.
 */
case class User(
    id: Option[String] = None,
    email: String,
    username: String,
    name: String,
    accountType: AccountType,
    isActive: Boolean = true,
    canCreateLibraries: Boolean = false,
    grantsNewLibraries: Boolean = false,
    newLibraryPermissions: Permissions = Permissions.none,
    createdByAccountId: Option[String] = None,
    lastLoginAt: Option[LocalDateTime] = None,
    lastActiveRepoId: Option[String] = None)
  extends BaseModel
  with NoDates:

  /** The name key `account_01` keeps unique, and a login by email compares */
  lazy val emailKey: String = NameKey.of(email)

  /** The name key `account_02` keeps unique, and a login by username compares */
  lazy val usernameKey: String = NameKey.of(username)

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, @$username, accountType: $accountType"
  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  def forgetMe(): Unit =
    println("User: this is where you'd invalidate the saved token in you User model")
