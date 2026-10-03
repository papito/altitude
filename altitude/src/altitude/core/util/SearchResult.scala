package altitude.core.util

import altitude.core.models.Asset

/**
 * One page of a flat search. `total` is present on a first page only, and counts the matches up to the query's `totalCap`: one
 * past the cap means more than it. `hasMore` says whether a page follows this one, which decides the next page number the last
 * cell carries.
 */
case class SearchResult(records: List[Asset], total: Option[Int], hasMore: Boolean, rpp: Int, page: Int, sort: List[SearchSort]):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
