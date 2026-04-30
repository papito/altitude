package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Stat

trait StatDao extends BaseDao[Stat]:
  def incrementStat(statName: String, count: Long = 1): Unit
  def decrementStat(statName: String, count: Long = 1): Unit =
    incrementStat(statName, -count)
