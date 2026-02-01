package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao

trait StatDao extends BaseDao:
  def incrementStat(statName: String, count: Long = 1): Unit
  def decrementStat(statName: String, count: Long = 1): Unit =
    incrementStat(statName, -count)
