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
      id = Some(rec(C.Base.ID).asInstanceOf[String]),
      email = rec(C.User.EMAIL).asInstanceOf[String],
      accountType = rec(C.User.ACCOUNT_TYPE).asInstanceOf[AccountType],
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${C.User.ID}, ${C.User.EMAIL}, ${C.User.ACCOUNT_TYPE}, ${C.User.PASSWORD_HASH})
             VALUES (?, ?, ?, ?)
    """

    val user: User = jsonIn: User

    val id = BaseDao.genId
    val passwordHash = (jsonIn \ C.User.PASSWORD_HASH).as[String]

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.accountType,
      passwordHash)

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(C.Base.ID -> id)
  }
}
