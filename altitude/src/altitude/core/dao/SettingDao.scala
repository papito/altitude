package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Setting

trait SettingDao extends BaseDao[Setting]:
  def getAll: List[Setting]

  def updateValues(values: Map[String, String]): Unit
