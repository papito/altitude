package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import software.altitude.core.FieldConst
import software.altitude.core.models.UserToken
import software.altitude.core.util.Util

abstract class UserTokenDao(override val config: Config) extends BaseDao with software.altitude.core.dao.UserTokenDao {
  override final val tableName = "user_token"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val expiresAtStr = rec(FieldConst.UserToken.EXPIRES_AT).asInstanceOf[String]

    UserToken(
      userId = rec(FieldConst.UserToken.ACCOUNT_ID).asInstanceOf[String],
      token = rec(FieldConst.UserToken.TOKEN).asInstanceOf[String],
      expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
    ).toJson
  }

  override def add(jsonIn: JsObject): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.UserToken.ACCOUNT_ID}, ${FieldConst.UserToken.TOKEN}, ${FieldConst.UserToken.EXPIRES_AT})
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
