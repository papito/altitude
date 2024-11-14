package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.FieldConst
import software.altitude.core.models.AccountType
import software.altitude.core.models.User

abstract class UserDao(override val config: Config) extends BaseDao with software.altitude.core.dao.UserDao {
  override final val tableName = "account"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    User(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      email = rec(FieldConst.User.EMAIL).asInstanceOf[String],
      name = rec(FieldConst.User.NAME).asInstanceOf[String],
      accountType = AccountType.withName(rec(FieldConst.User.ACCOUNT_TYPE).asInstanceOf[String]),
      lastActiveRepoId = Option(rec(FieldConst.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    )
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.User.EMAIL}, ${FieldConst.User.NAME},
                                ${FieldConst.User.ACCOUNT_TYPE}, ${FieldConst.User.PASSWORD_HASH},
                                ${FieldConst.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?)
    """

    val user: User = jsonIn: User

    val id = BaseDao.genId
    val passwordHash = (jsonIn \ FieldConst.User.PASSWORD_HASH).as[String]

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.name,
      user.accountType.toString,
      passwordHash,
      user.lastActiveRepoId.orNull
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(FieldConst.ID -> id)
  }
}
