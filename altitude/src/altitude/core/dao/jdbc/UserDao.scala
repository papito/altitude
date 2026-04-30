package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.models.AccountType
import altitude.core.models.User
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import com.typesafe.config.Config

import scala.language.implicitConversions

abstract class UserDao(override val config: Config) extends BaseDao with altitude.core.dao.UserDao:
  final override val tableName = "account"

  override protected def makeModel(rec: Map[String, AnyRef]): ujson.Obj =
    User(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      email = rec(FieldConst.User.EMAIL).asInstanceOf[String],
      name = rec(FieldConst.User.NAME).asInstanceOf[String],
      accountType = AccountType.valueOf(rec(FieldConst.User.ACCOUNT_TYPE).asInstanceOf[String]),
      lastActiveRepoId = Option(rec(FieldConst.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    ).toJson

  override def add(jsonIn: ujson.Obj): ujson.Obj =
    val sql = s"""
        INSERT INTO account (${FieldConst.ID}, ${FieldConst.User.EMAIL}, ${FieldConst.User.NAME},
                                ${FieldConst.User.ACCOUNT_TYPE}, ${FieldConst.User.PASSWORD_HASH},
                                ${FieldConst.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?)
    """

    val user: User = jsonIn

    val id = BaseDao.genId
    val passwordHash = jsonIn(FieldConst.User.PASSWORD_HASH).str

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.name,
      user.accountType.toString,
      passwordHash,
      user.lastActiveRepoId.orNull
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn(FieldConst.ID) = id
    jsonIn
