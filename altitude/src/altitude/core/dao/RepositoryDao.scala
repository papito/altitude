package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository

trait RepositoryDao extends BaseDao[Repository]:
  def getAll: List[Repository]

  /** The Libraries an account holds a grant in, in no order */
  def getGrantedTo(accountId: String): List[Repository]
