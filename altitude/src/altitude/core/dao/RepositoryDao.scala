package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository

object RepositoryDao:
  /** One Library per name key */
  val NAME_KEY_INDEX: BaseDao.UniqueIndex = BaseDao.UniqueIndex("repository_01", "repository.name_key")

trait RepositoryDao extends BaseDao[Repository]:
  def getAll: List[Repository]

  /** The Libraries an account holds a grant in, in no order */
  def getGrantedTo(accountId: String): List[Repository]
