package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Person

trait PersonDao extends BaseDao {
  def updateMergedWithIds(person: Person, newId: String): Person
  def getAll: Map[String, Person]
}
