package software.altitude.core.dao

import software.altitude.core.dao.jdbc.BaseDao

trait StatDao extends BaseDao {
  def incrementStat(statName: String, count: Long = 1): Unit
  def decrementStat(statName: String, count: Long = 1): Unit = {
    incrementStat(statName, -count)
  }
}
