package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import software.altitude.core.models.UserToken
import software.altitude.core.util.Util
import software.altitude.core.{Const => C}

abstract class UserTokenDao(override val config: Config) extends BaseDao with software.altitude.core.dao.UserTokenDao {
  override final val tableName = "user_token"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val expiresAtStr = rec(C.UserToken.EXPIRES_AT).asInstanceOf[String]

    UserToken(
      userId = rec(C.UserToken.ACCOUNT_ID).asInstanceOf[String],
      token = rec(C.UserToken.TOKEN).asInstanceOf[String],
      expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
    ).toJson
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${C.UserToken.ACCOUNT_ID}, ${C.UserToken.TOKEN}, ${C.UserToken.EXPIRES_AT})
             VALUES (?, ?, ?)
    """

    val userToken: UserToken = jsonIn: UserToken

    val sqlVals: List[Any] = List(
      userToken.userId,
      userToken.token,
      userToken.expiresAt
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn
  }
}
