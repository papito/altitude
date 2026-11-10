package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository

trait RepositoryDao extends BaseDao[Repository]:
  def getAll: List[Repository]

  /** The Libraries an account owns, in no particular order */
  def getOwnedBy(ownerAccountId: String): List[Repository]
