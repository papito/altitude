package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.RepositoryGrantRow
import altitude.core.models.Permissions
import altitude.core.models.RepositoryGrant

abstract class RepositoryGrantDao(override val config: Config)
  extends BaseDao[RepositoryGrant]
  with altitude.core.dao.RepositoryGrantDao:

  final override val tableName = "repository_grant"

  // Read and written across Libraries (a login, an account's grants), with no Library in the context
  final override protected val repositoryScoped = false

  final override type Row[T[_]] = RepositoryGrantRow[T]
  final override protected def table: Table[Row] = RepositoryGrantRow

  override protected def toModel(row: RepositoryGrantRow[Sc]): RepositoryGrant =
    RepositoryGrant(accountId = row.accountId, repositoryId = row.repositoryId, permissions = Permissions(row.permissions))

  override protected def makeModel(rec: Map[String, AnyRef]): RepositoryGrant =
    RepositoryGrant(
      accountId = rec(FieldConst.RepositoryGrant.ACCOUNT_ID).asInstanceOf[String],
      repositoryId = rec(FieldConst.REPO_ID).asInstanceOf[String],
      permissions = Permissions(getIntField(rec(FieldConst.RepositoryGrant.PERMISSIONS)))
    )

  // One statement on both engines (SQLite has had the upsert since 3.24), so a grant set twice at once leaves one row
  override def setGrant(accountId: String, repositoryId: String, permissions: Permissions): Unit =
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.RepositoryGrant.ACCOUNT_ID}, ${FieldConst.REPO_ID},
                                ${FieldConst.RepositoryGrant.PERMISSIONS})
             VALUES (?, ?, ?)
        ON CONFLICT (${FieldConst.RepositoryGrant.ACCOUNT_ID}, ${FieldConst.REPO_ID})
        DO UPDATE SET ${FieldConst.RepositoryGrant.PERMISSIONS} = excluded.${FieldConst.RepositoryGrant.PERMISSIONS}
    """
    addRecord(sql, List(accountId, repositoryId, permissions.mask))

  override def getByAccount(accountId: String): List[RepositoryGrant] =
    import dialect.*
    Db.read(dialect)(_.run(table.select.filter(_.accountId === accountId))).map(toModel).toList

  override def getGrant(accountId: String, repositoryId: String): Option[RepositoryGrant] =
    import dialect.*
    Db.read(dialect)(_.run(table.select.filter(row => row.accountId === accountId && row.repositoryId === repositoryId)))
      .headOption
      .map(toModel)
