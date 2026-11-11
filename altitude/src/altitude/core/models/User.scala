package altitude.core.models

import java.time.LocalDateTime

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.NameKey

object User:
  given JsonCodec.ReadWriter[User] = JsonCodec.macroRW
  given Conversion[ujson.Value, User] = json => JsonCodec.read[User](json)

  // ASCII letters, digits, dots, underscores and hyphens: never an "@", so a login with one is an email address and any other a
  // username, and never a character whose case or form folds
  private val USERNAME = s"[A-Za-z0-9._-]{${Api.Constraints.MIN_USERNAME_LENGTH},${Api.Constraints.MAX_USERNAME_LENGTH}}".r

  /**
   * `account` held to the rules of what it stores: its username's (`validUsername`), its name's (2 to 80 characters once
   * normalized, `Validators.validName`) and its email address's (3 to 80 ASCII characters of an address, `Validators.isEmail`).
   * The longest name and address these allow bound the login token's length (`RepositoryService.MAX_GRANTS_PER_ACCOUNT`). The
   * account with its name normalized, or a `ValidationException` with the error of every field that fails.
   */
  def valid(account: User): User =
    val errors = scala.collection.mutable.LinkedHashMap[String, String]()
    def check[A](rule: => A): Option[A] =
      try Some(rule)
      catch
        case e: ValidationException =>
          errors ++= e.errors
          None

    check(validUsername(account.username))
    val name = check(
      Validators.validName(account.name, FieldConst.User.NAME, Api.Constraints.MAX_NAME_LENGTH, Api.Constraints.MIN_NAME_LENGTH))
    check(validEmail(account.email))

    errors.headOption.foreach {
      (_, message) =>
        val ex = ValidationException(message)
        ex.errors ++= errors
        throw ex
    }
    account.copy(name = name.getOrElse(account.name))

  private def validEmail(email: String): Unit =
    val (min, max) = (Api.Constraints.MIN_EMAIL_LENGTH, Api.Constraints.MAX_EMAIL_LENGTH)
    val error =
      if email.isEmpty then Some(C.Msg.Err.VALUE_REQUIRED)
      else if email.length > max then Some(C.Msg.Err.VALUE_TOO_LONG.format(max))
      else if email.length < min then Some(C.Msg.Err.VALUE_TOO_SHORT.format(min))
      else if !Validators.isEmail(email) then Some(C.Msg.Err.VALUE_NOT_AN_EMAIL)
      else None

    error.foreach {
      message =>
        val ex = ValidationException(message)
        ex.errors += (FieldConst.User.EMAIL -> message)
        throw ex
    }

  /**
   * The username rule: 3 to 32 ASCII letters, digits, `.`, `_` and `-`. A username outside it is a `ValidationException` whose
   * error, under `FieldConst.User.USERNAME`, is `VALUE_REQUIRED` for an empty one and `USERNAME_RULE` otherwise.
   */
  def validUsername(username: String): Unit =
    val error =
      if username.isEmpty then Some(C.Msg.Err.VALUE_REQUIRED)
      else if !USERNAME.matches(username) then Some(C.Msg.Err.USERNAME_RULE)
      else None

    error.foreach {
      message =>
        val ex = ValidationException(message)
        ex.errors += (FieldConst.User.USERNAME -> message)
        throw ex
    }

/**
 * An account (`CONTEXT.md`). The email address and the username each name one account under their name keys ([[emailKey]] and
 * [[usernameKey]], `NameKey.of`), which `account_01` and `account_02` keep unique and a login compares; the username keeps to
 * [[User.validUsername]]. `canCreateLibraries` is the Create Libraries flag; `grantsNewLibraries` and `newLibraryPermissions` are
 * the new-Library grant: with the switch on, a Library created later grants the account those Permissions. `createdAt` is when
 * the account was added, as stored; a model about to be added has none.
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
    lastActiveRepoId: Option[String] = None,
    createdAt: Option[LocalDateTime] = None)
  extends BaseModel:

  val updatedAt: Option[LocalDateTime] = None

  /** The name key `account_01` keeps unique, and a login by email compares */
  lazy val emailKey: String = NameKey.of(email)

  /** The name key `account_02` keeps unique, and a login by username compares */
  lazy val usernameKey: String = NameKey.of(username)

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, @$username, accountType: $accountType"
  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  def forgetMe(): Unit =
    println("User: this is where you'd invalidate the saved token in you User model")
