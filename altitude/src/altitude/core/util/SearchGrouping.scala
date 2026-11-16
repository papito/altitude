package altitude.core.util

import altitude.core.FieldConst

/**
 * A Location grouping's groups are the user's Locations in path order, an asset under every Location it is in. A Sequence
 * grouping, the Triage view's alone, orders the Sequences by the first capture of their matching frames. Both end with a "No
 * location" or "No sequence" group.
 */
enum GroupBy(val apiValue: String, val dateField: Option[String]):
  case DateTaken extends GroupBy("dateTaken", Some(FieldConst.Asset.ORIGINAL_CREATED_AT))
  case Location extends GroupBy("location", None)
  case Sequence extends GroupBy("sequence", None)

object GroupBy:
  def fromApiValue(value: String): Option[GroupBy] = GroupBy.values.find(_.apiValue == value)

/**
 * Groups are the primary order of a grouped search, the query's sort applying within each. `direction` orders the days of a date
 * grouping; a Location or a Sequence grouping has a fixed order.
 */
case class SearchGrouping(by: GroupBy, direction: SortDirection = SortDirection.DESC)
