package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.models.AccountType.AccountType
import software.altitude.core.models.User
import software.altitude.core.{Const => C}

abstract class UserDao(override val config: Config) extends BaseDao with software.altitude.core.dao.UserDao {
  override final val tableName = "account"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = User(
      id = Option(rec(C.Base.ID).asInstanceOf[String]),
      email = rec(C.User.EMAIL).asInstanceOf[String],
      name = rec(C.User.NAME).asInstanceOf[String],
      accountType = rec(C.User.ACCOUNT_TYPE).asInstanceOf[AccountType],
      lastActiveRepoId = Option(rec(C.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${C.User.ID}, ${C.User.EMAIL}, ${C.User.NAME},
                                ${C.User.ACCOUNT_TYPE}, ${C.User.PASSWORD_HASH},
                                ${C.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?)
    """

    val user: User = jsonIn: User

    val id = BaseDao.genId
    val passwordHash = (jsonIn \ C.User.PASSWORD_HASH).as[String]

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.name,
      user.accountType,
      passwordHash,
      user.lastActiveRepoId.orNull
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(C.Base.ID -> id)
  }
}
