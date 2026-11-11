package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.AccountRow
import altitude.core.models.AccountType
import altitude.core.models.Permissions
import altitude.core.models.User

abstract class UserDao(override val config: Config) extends BaseDao[User] with altitude.core.dao.UserDao:
  final override val tableName = "account"

  // The table has no repository_id
  final override protected val repositoryScoped = false

  final override type Row[T[_]] = AccountRow[T]
  final override protected def table: Table[Row] = AccountRow

  override protected def toModel(row: AccountRow[Sc]): User =
    User(
      id = Option(row.id),
      email = row.email,
      username = row.username,
      name = row.name,
      accountType = AccountType.valueOf(row.accountType),
      isActive = row.isActive,
      canCreateLibraries = row.canCreateLibraries,
      grantsNewLibraries = row.grantsNewLibraries,
      newLibraryPermissions = Permissions(row.newLibraryPermissions),
      createdByAccountId = row.createdByAccountId,
      lastLoginAt = row.lastLoginAt.map(toLocalDateTime),
      lastActiveRepoId = row.lastActiveRepoId
    )

  override def getWithPasswordHash(key: String, byEmail: Boolean): Option[(User, String)] =
    import dialect.*

    // The key column the unique index is declared on, so the lookup and the index cannot differ
    Db.read(dialect)(_.run(AccountRow.select.filter(row => (if byEmail then row.emailKey else row.usernameKey) === key)))
      .headOption
      .map(row => (toModel(row), row.passwordHash))

  override def lockWithPasswordHash(id: String): Option[(User, String)] =
    manyBySqlQuery(s"SELECT * FROM account WHERE ${FieldConst.ID} = ? $forUpdate", List(id)).headOption
      .map(row => (makeModel(row), row(FieldConst.User.PASSWORD_HASH).asInstanceOf[String]))

  override def recordLogin(id: String): Unit =
    val sql = s"UPDATE account SET ${FieldConst.User.LAST_LOGIN_AT} = ? WHERE ${FieldConst.ID} = ?"
    updateByBySql(sql, List(nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC)), id)): Unit

  override protected def makeModel(rec: Map[String, AnyRef]): User =
    User(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      email = rec(FieldConst.User.EMAIL).asInstanceOf[String],
      username = rec(FieldConst.User.USERNAME).asInstanceOf[String],
      name = rec(FieldConst.User.NAME).asInstanceOf[String],
      accountType = AccountType.valueOf(rec(FieldConst.User.ACCOUNT_TYPE).asInstanceOf[String]),
      isActive = getBooleanField(rec(FieldConst.User.IS_ACTIVE)),
      canCreateLibraries = getBooleanField(rec(FieldConst.User.CAN_CREATE_LIBRARIES)),
      grantsNewLibraries = getBooleanField(rec(FieldConst.User.GRANTS_NEW_LIBRARIES)),
      newLibraryPermissions = Permissions(getIntField(rec(FieldConst.User.NEW_LIBRARY_PERMISSIONS))),
      createdByAccountId = Option(rec(FieldConst.User.CREATED_BY_ACCOUNT_ID).asInstanceOf[String]),
      lastLoginAt = getDateTimeField(rec.get(FieldConst.User.LAST_LOGIN_AT)),
      lastActiveRepoId = Option(rec(FieldConst.User.LAST_ACTIVE_REPO_ID).asInstanceOf[String])
    )

  /** The email address and the username are written beside their name keys; a key another account holds fails the insert */
  override def addUser(user: User, passwordHash: String): User =
    val sql = s"""
        INSERT INTO account (${FieldConst.ID}, ${FieldConst.User.EMAIL}, ${FieldConst.User.EMAIL_KEY},
                             ${FieldConst.User.USERNAME}, ${FieldConst.User.USERNAME_KEY}, ${FieldConst.User.NAME},
                             ${FieldConst.User.ACCOUNT_TYPE}, ${FieldConst.User.PASSWORD_HASH}, ${FieldConst.User.IS_ACTIVE},
                             ${FieldConst.User.CAN_CREATE_LIBRARIES}, ${FieldConst.User.GRANTS_NEW_LIBRARIES},
                             ${FieldConst.User.NEW_LIBRARY_PERMISSIONS}, ${FieldConst.User.CREATED_BY_ACCOUNT_ID},
                             ${FieldConst.User.LAST_ACTIVE_REPO_ID})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      user.email,
      user.emailKey,
      user.username,
      user.usernameKey,
      user.name,
      user.accountType.toString,
      passwordHash,
      user.isActive,
      user.canCreateLibraries,
      user.grantsNewLibraries,
      user.newLibraryPermissions.mask,
      user.createdByAccountId.orNull,
      user.lastActiveRepoId.orNull
    )

    addRecord(sql, sqlVals)
    user.copy(id = Some(id))

  // One conditional statement, so a load of the Library recorded already matches no row; SQLite reads IS DISTINCT FROM too
  override def rememberLastActiveRepository(userId: String, repoId: String): Boolean =
    val sql = s"""
        UPDATE account
           SET ${FieldConst.User.LAST_ACTIVE_REPO_ID} = ?, ${FieldConst.UPDATED_AT} = ?
         WHERE ${FieldConst.ID} = ? AND ${FieldConst.User.LAST_ACTIVE_REPO_ID} IS DISTINCT FROM ?
    """

    updateByBySql(sql, List(repoId, nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC)), userId, repoId)) > 0
