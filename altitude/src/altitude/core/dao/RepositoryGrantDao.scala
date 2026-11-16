package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Permissions
import altitude.core.models.RepositoryGrant

/** Not scoped to a Library: grants are read and written with none in the context (a login, an account's grants everywhere) */
trait RepositoryGrantDao extends BaseDao[RepositoryGrant]:

  /** Replaces the Permissions of a grant the account already holds there */
  def setGrant(accountId: String, repositoryId: String, permissions: Permissions): Unit

  /** Returns how many rows changed */
  def deleteByAccount(accountId: String): Int

  /** In no order */
  def getByAccounts(accountIds: Set[String]): List[RepositoryGrant]

  /** One per Library, in no order */
  def getByAccount(accountId: String): List[RepositoryGrant]

  def getGrant(accountId: String, repositoryId: String): Option[RepositoryGrant]
