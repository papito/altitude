package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Permissions
import altitude.core.models.RepositoryGrant

/**
 * The grants of accounts in Libraries. They are read and written with no Library in the context (a login, an account's grants
 * across Libraries), so the DAO is not scoped to one.
 */
trait RepositoryGrantDao extends BaseDao[RepositoryGrant]:

  /** Gives the account access to the Library with these Permissions, replacing the Permissions of a grant it holds there */
  def setGrant(accountId: String, repositoryId: String, permissions: Permissions): Unit

  /** Takes away the account's every grant; how many rows changed */
  def deleteByAccount(accountId: String): Int

  /** The grants of the accounts, in no particular order */
  def getByAccounts(accountIds: Set[String]): List[RepositoryGrant]

  /** The account's grants, one per Library, in no particular order */
  def getByAccount(accountId: String): List[RepositoryGrant]

  /** The account's grant in the Library, if it holds one */
  def getGrant(accountId: String, repositoryId: String): Option[RepositoryGrant]
