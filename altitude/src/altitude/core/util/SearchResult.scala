package altitude.core.util

import java.time.LocalDate

import altitude.core.models.Asset

/**
 * One page row of a flat search, as the DAO reads it: the asset with where it stands in the order. `day` is its capture day under
 * the capture-time sort, which is ordered by day first, and absent under any other. `secondSortValue` is the capture time as
 * stored under the Relevance sort, which orders by it next, and null under any other.
 */
case class SearchRow(asset: Asset, day: Option[LocalDate], sortValue: SortValue, secondSortValue: SortValue)

/**
 * What one flat search statement returns: the ordered page rows, whether a further match exists past the page and, on a first
 * page, the count of the matches up to the query's `totalCap`
 */
case class SearchPage(rows: List[SearchRow], total: Option[Int], hasMore: Boolean)

/**
 * One page of a flat search. `total` is present on a first page only, and counts the matches up to the query's `totalCap`: one
 * past the cap means more than it. `nextCursor` is where the next page continues from, the last asset of this one, and is absent
 * on the last page.
 */
case class SearchResult(
    records: List[Asset],
    total: Option[Int],
    nextCursor: Option[SearchCursor],
    rpp: Int,
    sort: List[SearchSort]):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
