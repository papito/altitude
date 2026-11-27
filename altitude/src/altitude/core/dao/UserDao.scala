package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.User

object UserDao:
  /** The name keys that keep one account per email address and per username */
  val EMAIL_KEY_INDEX: BaseDao.UniqueIndex = BaseDao.UniqueIndex("account_01", "account.email_key")
  val USERNAME_KEY_INDEX: BaseDao.UniqueIndex = BaseDao.UniqueIndex("account_02", "account.username_key")

trait UserDao extends BaseDao[User]:
  def addUser(user: User, passwordHash: String): User

  /**
   * The account whose `email_key` (`byEmail`) or `username_key` is `key`, already folded (`NameKey.of`), with its password hash,
   * which the `User` model never carries
   */
  def getWithPasswordHash(key: String, byEmail: Boolean): Option[(User, String)]

  /**
   * The account and its password hash, its row locked for the transaction (on PostgreSQL; SQLite's writers take turns anyway), so
   * a write in flight is waited for and read
   */
  def lockWithPasswordHash(id: String): Option[(User, String)]

  /** [[lockWithPasswordHash]] without the hash */
  def lockById(id: String): User =
    lockWithPasswordHash(id).map(_._1).getOrElse(throw altitude.core.NotFoundException(s"No account [$id]"))

  /**
   * The account, share-locked against a delete or a locking write (`FOR KEY SHARE` on PostgreSQL) for the transaction, so it
   * stays as read while the caller writes rows that reference it: an import's asset, an Edit, a Watch folder
   */
  def shareLock(id: String): Option[User]

  /** In no order */
  def getAll: List[User]

  /** The accounts `accountId` created, in no order */
  def getCreatedBy(accountId: String): List[User]

  /**
   * The accounts a new Library grants access to (those with the new-Library grant on, and its creator), locked in ID order for
   * the transaction on PostgreSQL
   */
  def lockGrantees(creatorId: Option[String]): List[User]

  /**
   * The active Admins, locked in ID order for the transaction (on PostgreSQL; SQLite's writers take turns anyway): a write that
   * may leave fewer of them takes this first, so the second of two counts what the first left. `FOR NO KEY UPDATE`, which two
   * such writes take in turn but a foreign key check or [[shareLock]] does not wait for, so an Admin's own import, Edit or Watch
   * folder edit goes on during another Admin's account write; a write of one of them as its target locks it `FOR UPDATE` again
   * ([[lockById]]), which they then wait for.
   */
  def lockActiveAdmins(): List[User]

  /**
   * Writes the account's email, username (each with its name key), name, account type, Create Libraries flag and new-Library
   * grant; how many rows changed. A key another account holds fails the update.
   */
  def update(account: User): Int

  /** Returns how many rows changed */
  def setActive(id: String, active: Boolean): Int

  /** Returns how many rows changed */
  def setPasswordHash(id: String, passwordHash: String): Int

  /** Not an account change, so `updated_at` stays */
  def recordLogin(id: String): Unit

  /** Writes nothing when `repoId` is already the one recorded; whether it wrote. Not an account change, so `updated_at` stays. */
  def rememberLastActiveRepository(userId: String, repoId: String): Boolean
