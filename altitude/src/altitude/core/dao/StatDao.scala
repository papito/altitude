package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Stat

trait StatDao extends BaseDao[Stat]:
  def incrementStat(statName: String, count: Long = 1): Unit

  /** Sets the context repository's six stats to what its assets are, and returns the ones that differed, with their old values */
  def reconcile(): Map[String, Long]
