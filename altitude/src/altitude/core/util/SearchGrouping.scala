package altitude.core.util

import altitude.core.FieldConst

/** The date a search result is grouped by: the API value the client sends, and the asset column behind it */
enum GroupBy(val apiValue: String, val field: String):
  case DateTaken extends GroupBy("dateTaken", FieldConst.Asset.ORIGINAL_CREATED_AT)
  case DateImported extends GroupBy("dateImported", FieldConst.CREATED_AT)

object GroupBy:
  def fromApiValue(value: String): Option[GroupBy] = GroupBy.values.find(_.apiValue == value)

/** Date groups are the primary order of a grouped search; the query's sort applies independently within each day */
case class SearchGrouping(by: GroupBy, direction: SortDirection = SortDirection.DESC)
