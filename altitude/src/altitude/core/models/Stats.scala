package altitude.core.models

object Stats:
  final val TOTAL_ASSETS = "total_assets"
  final val TOTAL_BYTES = "total_bytes"
  final val SORTED_ASSETS = "sorted_assets"
  final val SORTED_BYTES = "sorted_bytes"
  final val TRIAGE_ASSETS = "triage_assets"
  final val TRIAGE_BYTES = "triage_bytes"
  final val RECYCLED_ASSETS = "recycled_assets"
  final val RECYCLED_BYTES = "recycled_bytes"

case class Stats(stats: List[Stat]):
  private val lookup: Map[String, Stat] = stats.foldLeft(Map[String, Stat]())((res, stat) => res + (stat.dimension -> stat))

  def getStatValue(key: String): Long =
    if !lookup.contains(key) then throw RuntimeException(s"No stats for '$key'")

    lookup(key).dimVal

  def getTotalAssetCount: Long =
    getStatValue(Stats.TOTAL_ASSETS)

  def getTotalBytes: Long =
    getStatValue(Stats.TOTAL_BYTES)

  def getSortedAssetCount: Long =
    getStatValue(Stats.SORTED_ASSETS)

  def getSortedBytes: Long =
    getStatValue(Stats.SORTED_BYTES)

  def getTriageAssetCount: Long =
    getStatValue(Stats.TRIAGE_ASSETS)

  def getTriageBytes: Long =
    getStatValue(Stats.TRIAGE_BYTES)

  def getRecycledAssetCount: Long =
    getStatValue(Stats.RECYCLED_ASSETS)

  def getRecycledBytes: Long =
    getStatValue(Stats.RECYCLED_BYTES)
