package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Stat

trait StatDao extends BaseDao[Stat]:
  def incrementStat(statName: String, count: Long = 1): Unit

  /** Recounts the repository's stats from its assets; returns those that differed, with their old values */
  def reconcile(): Map[String, Long]
