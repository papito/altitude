package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.User

trait UserDao extends BaseDao[User]:
  def addUser(user: User, passwordHash: String): User

  /** The stored hash for an account. It is not part of the `User` model, so it never travels with one. */
  def getPasswordHashByEmail(email: String): String
