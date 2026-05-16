package altitude.core.util

import altitude.core.models.Asset

case class SearchResult(records: List[Asset], total: Int, rpp: Int, page: Int, sort: List[SearchSort]):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
  val totalPages: Int = Math.ceil(total / rpp.toDouble).toInt
  // used in templates
  val hasMoreResults: Boolean = page < totalPages
