package altitude.core.util

import java.time.LocalDate

import altitude.core.models.Asset

/**
 * The group a grouped page row belongs to: a capture day (`None` is the "No date" group) or a Location, with what the grid needs
 * to head it (all `None` is the "No location" group). `pathKey` is the value the Location groups are ordered by.
 */
enum SearchGroupKey:
  case Day(date: Option[LocalDate])
  case Location(id: Option[String], pathKey: Option[String], name: Option[String], parentName: Option[String])

  /** The ordering key a cursor remembers for the group: the ISO day, or the Location's path key */
  def cursorKey: Option[String] = this match
    case Day(date) => date.map(_.toString)
    case Location(_, pathKey, _, _) => pathKey

  /** The group's ID where the key alone does not name it: the Location's */
  def cursorGroupId: Option[String] = this match
    case Day(_) => None
    case Location(id, _, _, _) => id

  /** Whether this is the group the cursor's anchor was in */
  def continues(cursor: SearchCursor): Boolean = cursorKey == cursor.key && cursorGroupId == cursor.groupId

/** One page row of a grouped search, as the DAO reads it: the asset with its group, its sort key as stored, and its group's count */
case class GroupedSearchRow(asset: Asset, group: SearchGroupKey, sortValue: SortValue, groupTotal: Int)

/**
 * What one grouped search statement returns: the ordered page rows, whether a further match exists past the page and, on a first
 * page, the count of every match. A page reached by cursor skips that count: the footer total was set by the first page.
 */
case class GroupedSearchPage(rows: List[GroupedSearchRow], total: Option[Int], hasMore: Boolean)

/** One group of a grouped page: its key, its full count across all pages, and its assets on this page */
case class AssetGroup(key: SearchGroupKey, total: Int, assets: List[Asset])

/**
 * A grouped page with its assets in page order: what the grouped grid renders. `total` is present on a first page only.
 * `continuesGroup` says the first group continues the previous page's last group, including the "No date" / "No location" group.
 */
case class GroupedSearchResult(
    groups: List[AssetGroup],
    total: Option[Int],
    grouping: SearchGrouping,
    sort: SearchSort,
    nextCursor: Option[SearchCursor],
    continuesGroup: Boolean):
  val assets: List[Asset] = groups.flatMap(_.assets)
  val isEmpty: Boolean = groups.isEmpty

object GroupedSearchResult:

  /** Consecutive rows of one group form one group, in page order */
  def groupsOf(rows: List[GroupedSearchRow]): List[AssetGroup] =
    rows
      .foldLeft(List.empty[AssetGroup]) {
        case (last :: earlier, row) if last.key == row.group => last.copy(assets = last.assets :+ row.asset) :: earlier
        case (groups, row) => AssetGroup(key = row.group, total = row.groupTotal, assets = List(row.asset)) :: groups
      }
      .reverse
