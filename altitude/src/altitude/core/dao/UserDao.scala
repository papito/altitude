package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.User

trait UserDao extends BaseDao[User]:
  def addUser(user: User, passwordHash: String): User

  /**
   * The stored hash of the account an email address names, as [[getByEmail]] finds it. It is not part of the `User` model, so it
   * never travels with one.
   */
  def getPasswordHashByEmail(email: String): String

  /**
   * The account an email address names, whatever its case or Unicode form (its name key against `email_key`); a
   * `NotFoundException` when it names none
   */
  def getByEmail(email: String): User

  /**
   * Records `repoId` as the account's last active Library, stamping `updated_at`, unless it is the one recorded already, in which
   * case nothing is written. Whether it wrote.
   */
  def rememberLastActiveRepository(userId: String, repoId: String): Boolean
