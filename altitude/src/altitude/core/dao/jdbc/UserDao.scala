package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.models.AccountType
import altitude.core.models.User
import com.typesafe.config.Config

abstract class UserDao(override val config: Config) extends BaseDao[User] with altitude.core.dao.UserDao:
  final override val tableName = "account"

  override protected def makeModel(rec: Map[String, AnyRef]): User =
    User(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      email = rec(FieldConst.User.EMAIL).asInstanceOf[String],
      name = rec(FieldConst.User.NAME).asInstanceOf[String],
      accountType = AccountType.valueOf(rec(FieldConst.User.ACCOUNT_TYPE).asInstanceOf[String]),
      lastActiveRepoId = Option(rec(FieldConst.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    )

  override def addUser(user: User, passwordHash: String): User =
    val sql = s"""
        INSERT INTO account (${FieldConst.ID}, ${FieldConst.User.EMAIL}, ${FieldConst.User.NAME},
                                ${FieldConst.User.ACCOUNT_TYPE}, ${FieldConst.User.PASSWORD_HASH},
                                ${FieldConst.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?)
    """

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.name,
      user.accountType.toString,
      passwordHash,
      user.lastActiveRepoId.orNull
    )

    addRecord(sql, sqlVals)
    user.copy(id = Some(id))
