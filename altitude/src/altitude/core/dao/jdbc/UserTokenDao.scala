package altitude.core.dao.jdbc

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.models.UserToken
import altitude.core.util.Util

abstract class UserTokenDao(override val config: Config) extends BaseDao[UserToken] with altitude.core.dao.UserTokenDao:
  final override val tableName = "user_token"

  override protected def makeModel(rec: Map[String, AnyRef]): UserToken =
    val expiresAtStr = rec(FieldConst.UserToken.EXPIRES_AT).asInstanceOf[String]

    UserToken(
      userId = rec(FieldConst.UserToken.ACCOUNT_ID).asInstanceOf[String],
      token = rec(FieldConst.UserToken.TOKEN).asInstanceOf[String],
      expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
    )

  override def add(userToken: UserToken): UserToken =
    val sql = s"""
        INSERT INTO user_token (${FieldConst.UserToken.ACCOUNT_ID},
                                ${FieldConst.UserToken.TOKEN},
                                ${FieldConst.UserToken.EXPIRES_AT})
             VALUES (?, ?, ?)
    """

    val sqlVals: List[Any] = List(
      userToken.userId,
      userToken.token,
      userToken.expiresAt
    )

    addRecord(sql, sqlVals)
    userToken
