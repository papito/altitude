package altitude.core.util

import altitude.core.FieldConst

/**
 * What a search result is grouped by: the API value the client sends and, for a date grouping, the asset column behind it. A
 * Location grouping has no date column: its groups are the user's Locations in path order, an asset under every Location it is
 * in, and a trailing "No location" group.
 */
enum GroupBy(val apiValue: String, val dateField: Option[String]):
  case DateTaken extends GroupBy("dateTaken", Some(FieldConst.Asset.ORIGINAL_CREATED_AT))
  case Location extends GroupBy("location", None)

object GroupBy:
  def fromApiValue(value: String): Option[GroupBy] = GroupBy.values.find(_.apiValue == value)

/**
 * Groups are the primary order of a grouped search; the query's sort applies independently within each group. The direction
 * orders the days of a date grouping; a Location grouping has a fixed order and ignores it.
 */
case class SearchGrouping(by: GroupBy, direction: SortDirection = SortDirection.DESC)
