package altitude.core.dao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.User

trait UserDao extends BaseDao[User]:
  def addUser(user: User, passwordHash: String): User
