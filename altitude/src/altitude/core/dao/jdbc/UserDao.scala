package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.AccountRow
import altitude.core.models.AccountType
import altitude.core.models.User

abstract class UserDao(override val config: Config) extends BaseDao[User] with altitude.core.dao.UserDao:
  final override val tableName = "account"

  final override type Row[T[_]] = AccountRow[T]
  final override protected def table: Table[Row] = AccountRow

  override protected def toModel(row: AccountRow[Sc]): User =
    User(
      id = Option(row.id),
      email = row.email,
      name = row.name,
      accountType = AccountType.valueOf(row.accountType),
      lastActiveRepoId = row.lastActiveRepoId
    )

  /**
   * The password hash is deliberately not part of the `User` model, so it is read on its own. An unknown account is a
   * `NotFoundException`, which is what the login path expects to catch.
   */
  override def getPasswordHashByEmail(email: String): String =
    import dialect.*

    val hashes = Db.read(dialect)(_.run(AccountRow.select.filter(_.email === email).map(_.passwordHash)))

    if hashes.isEmpty then throw NotFoundException("No account with that email address")

    if hashes.length > 1 then throw ConstraintException("More than one account with the same email address")

    hashes.head

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
