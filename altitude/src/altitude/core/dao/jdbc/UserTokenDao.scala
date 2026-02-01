package altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject

import altitude.core.FieldConst
import altitude.core.models.UserToken
import altitude.core.util.Util

import scala.language.implicitConversions

abstract class UserTokenDao(override val config: Config) extends BaseDao with altitude.core.dao.UserTokenDao:
  final override val tableName = "user_token"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject =
    val expiresAtStr = rec(FieldConst.UserToken.EXPIRES_AT).asInstanceOf[String]

    UserToken(
      userId = rec(FieldConst.UserToken.ACCOUNT_ID).asInstanceOf[String],
      token = rec(FieldConst.UserToken.TOKEN).asInstanceOf[String],
      expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
    ).toJson

  override def add(jsonIn: JsObject): JsObject =
    val sql = s"""
        INSERT INTO user_token (${FieldConst.UserToken.ACCOUNT_ID},
                                ${FieldConst.UserToken.TOKEN},
                                ${FieldConst.UserToken.EXPIRES_AT})
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
