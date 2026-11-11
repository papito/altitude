package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.User

trait UserDao extends BaseDao[User]:
  def addUser(user: User, passwordHash: String): User

  /**
   * The account whose `email_key` (`byEmail`) or `username_key` is `key`, a name key the caller folded (`NameKey.of`), with its
   * stored password hash, which is not part of the `User` model, so it never travels with one; None when no account holds the key
   */
  def getWithPasswordHash(key: String, byEmail: Boolean): Option[(User, String)]

  /**
   * The account and its stored password hash, its row locked until the transaction ends (on PostgreSQL; SQLite's writers take
   * turns anyway), so a write of it in flight is waited for and read; None when there is no such account
   */
  def lockWithPasswordHash(id: String): Option[(User, String)]

  /** [[lockWithPasswordHash]] without the hash, and a `NotFoundException` when there is no such account */
  def lockById(id: String): User =
    lockWithPasswordHash(id).map(_._1).getOrElse(throw altitude.core.NotFoundException(s"No account [$id]"))

  /** Stamps the account's last login with the current time; an account change it is not, so `updated_at` stays */
  def recordLogin(id: String): Unit

  /**
   * Records `repoId` as the account's last active Library, stamping `updated_at`, unless it is the one recorded already, in which
   * case nothing is written. Whether it wrote.
   */
  def rememberLastActiveRepository(userId: String, repoId: String): Boolean
