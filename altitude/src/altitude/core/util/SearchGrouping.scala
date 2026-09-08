package altitude.core.util

import altitude.core.FieldConst

/** The date a search result is grouped by: the API value the client sends, and the asset column behind it */
enum NullDays:
  /** Legacy missing import times do not belong to an import day or its totals. */
  case Excluded

  /** Unknown capture times form one group at the engine's native null position. */
  case OwnGroup

enum GroupBy(val apiValue: String, val field: String, val nullDays: NullDays):
  case DateTaken extends GroupBy("dateTaken", FieldConst.Asset.ORIGINAL_CREATED_AT, NullDays.OwnGroup)
  case DateImported extends GroupBy("dateImported", FieldConst.CREATED_AT, NullDays.Excluded)

object GroupBy:
  def fromApiValue(value: String): Option[GroupBy] = GroupBy.values.find(_.apiValue == value)

/** Date groups are the primary order of a grouped search; the query's sort applies independently within each day */
case class SearchGrouping(by: GroupBy, direction: SortDirection = SortDirection.DESC)
