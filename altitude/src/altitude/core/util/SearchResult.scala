package altitude.core.util

import java.time.LocalDate

import altitude.core.models.Asset

/**
 * One page row of a flat search, as the DAO reads it: the asset with where it stands in the order. `day` is its capture day under
 * the capture-time sort, which is ordered by day first, and absent under any other. `secondSortValue` is the capture time as
 * stored under the Relevance sort, which orders by it next, and null under any other. A Describe search's row stands at the
 * cosine distance of its Scene embedding from the description's, a [[SortValue.Real]], with no day and no second value.
 */
case class SearchRow(asset: Asset, day: Option[LocalDate], sortValue: SortValue, secondSortValue: SortValue)

/**
 * Where the weaker matches of a Describe search begin, on the one page that holds the first of them: a result is a weaker match
 * when the cosine of its Scene embedding to the description's is under a fraction (`DescribeRanking.weakerRatio`) of the best
 * match's, so they are the tail of the ranking. `index` is the first weaker row's place on the page, and `count` how many of the
 * search's results are weaker, on this page and after it. The best match is never one, and a search whose best match has no
 * positive cosine has no such line.
 */
case class WeakerMatches(index: Int, count: Int)

/**
 * What one flat search statement returns: the ordered page rows, whether a further match exists past the page and, on a page that
 * counts it (`SearchQuery.isTotalCounted`), the count of the matches up to the query's `totalCap`. A Describe search's first page
 * counts the rows it ranked, which its result limit bounds, so its total is exact, and the page of a Describe search on which its
 * weaker matches begin says where (`weaker`).
 */
case class SearchPage(rows: List[SearchRow], total: Option[Int], hasMore: Boolean, weaker: Option[WeakerMatches] = None)

/**
 * One page of a flat search. `total` is present on a first page only, unless its search counts no total
 * (`SearchQuery.withoutTotal`), and counts the matches up to the query's `totalCap`: one past the cap means more than it.
 * `nextCursor` is where the next page continues from, the last asset of this one, and is absent on the last page. `weaker` is
 * where a Describe search's weaker matches begin among `records`, on the page that holds the first of them.
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
