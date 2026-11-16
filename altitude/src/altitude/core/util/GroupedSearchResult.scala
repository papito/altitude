package altitude.core.util

import java.time.LocalDate
import java.time.LocalDateTime

import altitude.core.models.Asset

/**
 * The group a grouped page row belongs to. All `None` is the "No date", "No location" or "No sequence" group; a Sequence whose
 * matching frames have no capture time has its ID alone. Location groups are ordered by `pathKey`, Sequence groups by
 * `firstCapturedAt`, the first capture of the matching frames.
 */
enum SearchGroupKey:
  case Day(date: Option[LocalDate])
  case Location(id: Option[String], pathKey: Option[String], name: Option[String], categoryName: Option[String])
  case Sequence(id: Option[String], firstCapturedAt: Option[LocalDateTime])

  /** The ordering key a cursor remembers for the group */
  def cursorKey: Option[String] = this match
    case Day(date) => date.map(_.toString)
    case Location(_, pathKey, _, _) => pathKey
    case Sequence(_, firstCapturedAt) => firstCapturedAt.map(_.toString)

  /** The group's ID where the key alone does not name it */
  def cursorGroupId: Option[String] = this match
    case Day(_) => None
    case Location(id, _, _, _) => id
    case Sequence(id, _) => id

  /**
   * Whether this is the group the cursor's anchor was in. A Sequence matches by ID alone: its first capture moves when the frame
   * that set it leaves, and its other frames still belong under the header already on screen.
   */
  def continues(cursor: SearchCursor): Boolean = this match
    case Sequence(id, _) => id == cursor.groupId
    case _ => cursorKey == cursor.key && cursorGroupId == cursor.groupId

/**
 * One page row of a grouped search, as the DAO reads it. `secondSortValue` is the stored capture time under the Relevance sort,
 * which orders by it next, and null under any other.
 */
case class GroupedSearchRow(
    asset: Asset,
    group: SearchGroupKey,
    sortValue: SortValue,
    secondSortValue: SortValue,
    groupTotal: Int)

/**
 * What one grouped search statement returns. `total` is counted on a first page only, since the first page already set the footer
 * total, and not at all for a search without one (`SearchQuery.withoutTotal`).
 */
case class GroupedSearchPage(rows: List[GroupedSearchRow], total: Option[Int], hasMore: Boolean)

/** `total` counts the group across all pages; `assets` are its assets on this page */
case class AssetGroup(key: SearchGroupKey, total: Int, assets: List[Asset])

/**
 * A grouped page, as the grouped grid renders it. `continuesGroup` says the first group continues the previous page's last one.
 * `weaker` is where a Describe search's weaker matches begin among `groups`, on the page that holds the first of them.
 */
case class GroupedSearchResult(
    groups: List[AssetGroup],
    total: Option[Int],
    grouping: SearchGrouping,
    sort: SearchSort,
    nextCursor: Option[SearchCursor],
    continuesGroup: Boolean,
    weaker: Option[WeakerMatches] = None):
  val assets: List[Asset] = groups.flatMap(_.assets)
  val isEmpty: Boolean = groups.isEmpty

object GroupedSearchResult:

  /** Each run of consecutive rows of one group forms one group, in page order */
  def groupsOf(rows: List[GroupedSearchRow]): List[AssetGroup] =
    List.unfold(rows) {
      case remaining @ (first :: _) =>
        val (run, rest) = remaining.span(_.group == first.group)
        Some((AssetGroup(key = first.group, total = first.groupTotal, assets = run.map(_.asset)), rest))
      case Nil => None
    }
