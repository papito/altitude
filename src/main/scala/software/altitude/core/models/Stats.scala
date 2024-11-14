package software.altitude.core.models

object Stats {
  final val TOTAL_ASSETS = "total_assets"
  final val TOTAL_BYTES = "total_bytes"
  final val SORTED_ASSETS = "sorted_assets"
  final val SORTED_BYTES = "sorted_bytes"
  final val TRIAGE_ASSETS = "triage_assets"
  final val TRIAGE_BYTES = "triage_bytes"
  final val RECYCLED_ASSETS = "recycled_assets"
  final val RECYCLED_BYTES = "recycled_bytes"
}

case class Stats(stats: List[Stat]) {
  private val lookup: Map[String, Stat] = stats.foldLeft(Map[String, Stat]()) {
    (res, stat) => res + (stat.dimension -> stat)
  }

  def getStatValue(key: String): Int = {
    if (!lookup.contains(key)) {
      throw new RuntimeException(s"No stats for '$key'")
    }

    lookup(key).dimVal
  }
}
