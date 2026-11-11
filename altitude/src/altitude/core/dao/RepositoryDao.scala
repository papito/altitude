package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository

trait RepositoryDao extends BaseDao[Repository]:
  def getAll: List[Repository]

  /** The Libraries an account holds a grant in (`repository_grant`), in no particular order */
  def getGrantedTo(accountId: String): List[Repository]
