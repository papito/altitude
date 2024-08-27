package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.models.AccountType.AccountType
import software.altitude.core.models.Field
import software.altitude.core.models.User

abstract class UserDao(override val config: Config) extends BaseDao with software.altitude.core.dao.UserDao {
  override final val tableName = "account"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = User(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      email = rec(Field.User.EMAIL).asInstanceOf[String],
      name = rec(Field.User.NAME).asInstanceOf[String],
      accountType = rec(Field.User.ACCOUNT_TYPE).asInstanceOf[AccountType],
      lastActiveRepoId = Option(rec(Field.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${Field.ID}, ${Field.User.EMAIL}, ${Field.User.NAME},
                                ${Field.User.ACCOUNT_TYPE}, ${Field.User.PASSWORD_HASH},
                                ${Field.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?)
    """

    val user: User = jsonIn: User

    val id = BaseDao.genId
    val passwordHash = (jsonIn \ Field.User.PASSWORD_HASH).as[String]

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.name,
      user.accountType,
      passwordHash,
      user.lastActiveRepoId.orNull
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(Field.ID -> id)
  }
}
