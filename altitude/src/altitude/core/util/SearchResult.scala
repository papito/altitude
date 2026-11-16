package altitude.core.util

import java.time.LocalDate

import altitude.core.models.Asset

/**
 * One page row of a flat search, as the DAO reads it. `day` is the capture day under the capture-time sort, which orders by day
 * first; `secondSortValue` the stored capture time under the Relevance sort, which orders by it next, and null under any other. A
 * Describe search's row stands at its cosine distance from the description, a [[SortValue.Real]], with neither.
 */
case class SearchRow(asset: Asset, day: Option[LocalDate], sortValue: SortValue, secondSortValue: SortValue)

/**
 * Where a Describe search's weaker matches begin, on the page that holds the first of them: the results whose cosine to the
 * description is under `DescribeRanking.weakerRatio` of the best match's. Sorted or grouped, the search shows the other matches
 * first. `index` is a place among the page's assets, or its groups on a grouped page; `count` the weaker results from this page
 * on. The best match is never one, and a best match with no positive cosine means no weaker matches.
 */
case class WeakerMatches(index: Int, count: Int)

/**
 * What one flat search statement returns. `total` is counted only where `SearchQuery.isTotalCounted`, up to the query's
 * `totalCap`; a Describe search's is exact, since its result limit bounds the rows it ranks.
 */
case class SearchPage(rows: List[SearchRow], total: Option[Int], hasMore: Boolean, weaker: Option[WeakerMatches] = None)

/**
 * One page of a flat search. A `total` one past the query's `totalCap` means more than the cap. `nextCursor` is this page's last
 * asset, absent on the last page.
 */
case class SearchResult(
    records: List[Asset],
    total: Option[Int],
    nextCursor: Option[SearchCursor],
    rpp: Int,
    sort: List[SearchSort],
    weaker: Option[WeakerMatches] = None):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
