package altitude.core.util

import java.time.LocalDate

/** One page row of a grouped ID search, as the DAO reads it */
case class IdSearchRow(id: String, day: LocalDate, sortValue: SortValue, dayTotal: Int)

/**
 * What one grouped ID search statement returns: the ordered page rows, the count of every match across all pages, and whether a
 * further match exists past this page.
 */
case class IdSearchPage(rows: List[IdSearchRow], total: Int, hasMore: Boolean)

/**
 * A run of consecutive page IDs sharing one date. `startIndex` and `length` index the page's ID list; `total` is the whole day's
 * match count across all pages.
 */
case class IdSearchGroup(date: LocalDate, startIndex: Int, length: Int, total: Int)

object IdSearchResult:
  /** Ranges are contiguous, non-overlapping and cover the whole page, in page order */
  def groupsOf(rows: List[IdSearchRow]): List[IdSearchGroup] =
    rows.zipWithIndex
      .foldLeft(List.empty[IdSearchGroup]) {
        case (last :: earlier, (row, _)) if last.date == row.day => last.copy(length = last.length + 1) :: earlier
        case (groups, (row, index)) =>
          IdSearchGroup(date = row.day, startIndex = index, length = 1, total = row.dayTotal) :: groups
      }
      .reverse

case class IdSearchResult(
    ids: List[String],
    groups: List[IdSearchGroup],
    total: Int,
    rpp: Int,
    page: Int,
    grouping: SearchGrouping,
    sort: SearchSort,
    nextCursor: Option[SearchCursor]):
  val totalPages: Int = Math.ceil(total / rpp.toDouble).toInt
