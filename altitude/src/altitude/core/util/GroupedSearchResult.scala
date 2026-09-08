package altitude.core.util

import java.time.LocalDate

import altitude.core.models.Asset

/** One page row of a grouped search, as the DAO reads it: the asset with its day, its sort key as stored, and its day's count */
case class GroupedSearchRow(asset: Asset, day: Option[LocalDate], sortValue: SortValue, dayTotal: Int)

/**
 * What one grouped search statement returns: the ordered page rows, whether a further match exists past the page and, on a first
 * page, the count of every match. A page reached by cursor skips that count: the footer total was set by the first page.
 */
case class GroupedSearchPage(rows: List[GroupedSearchRow], total: Option[Int], hasMore: Boolean)

/** One day of a grouped page: its calendar day, its full match count across all pages, and its assets on this page */
case class AssetDateGroup(date: Option[LocalDate], total: Int, assets: List[Asset])

/**
 * A grouped page with its assets in page order: what the grouped grid renders. `total` is present on a first page only.
 * `continuesGroup` says the first group continues the previous page's last group, including the undated group (date = None).
 */
case class GroupedSearchResult(
    groups: List[AssetDateGroup],
    total: Option[Int],
    grouping: SearchGrouping,
    sort: SearchSort,
    nextCursor: Option[SearchCursor],
    continuesGroup: Boolean):
  val assets: List[Asset] = groups.flatMap(_.assets)
  val isEmpty: Boolean = groups.isEmpty

object GroupedSearchResult:

  /** Consecutive rows of one day form one group, in page order */
  def groupsOf(rows: List[GroupedSearchRow]): List[AssetDateGroup] =
    rows
      .foldLeft(List.empty[AssetDateGroup]) {
        case (last :: earlier, row) if last.date == row.day => last.copy(assets = last.assets :+ row.asset) :: earlier
        case (groups, row) => AssetDateGroup(date = row.day, total = row.dayTotal, assets = List(row.asset)) :: groups
      }
      .reverse
