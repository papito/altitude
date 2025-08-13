package software.altitude.core.dao
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Repository

trait RepositoryDao extends BaseDao {
  def getAll: List[Repository]
}
