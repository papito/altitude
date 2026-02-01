package altitude.core.dao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository

trait RepositoryDao extends BaseDao:
  def getAll: List[Repository]
