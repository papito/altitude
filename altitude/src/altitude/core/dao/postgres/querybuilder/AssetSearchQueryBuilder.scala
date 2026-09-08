package altitude.core.dao.postgres.querybuilder

import java.time.LocalDate

import altitude.core.FieldConst
import altitude.core.dao.jdbc.querybuilder.ClauseComponents
import altitude.core.dao.jdbc.querybuilder.SearchQueryBuilder
import altitude.core.util.GroupBy
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection

class AssetSearchQueryBuilder(sqlColsForSelect: List[String]) extends SearchQueryBuilder(selColumnNames = sqlColsForSelect):

  protected def textSearch(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.isText then
      ClauseComponents(elements = List(s"$searchDocumentTable.tsv @@ to_tsquery(?)"), bindVals = List(searchQuery.text.get))
    else ClauseComponents()

  // Capture time is a wall-clock `timestamp`; import time is an instant whose calendar day is taken in UTC
  override protected def dayExpression(groupBy: GroupBy): String = groupBy match
    case GroupBy.DateTaken => s"$tableName.${groupBy.field}::date"
    case GroupBy.DateImported => s"($tableName.${groupBy.field} AT TIME ZONE 'UTC')::date"

  override protected def secondarySortExpression(sort: SearchSort, grouping: SearchGrouping): String =
    s"$tableName.${sort.field}"

  // Capture time is unknown when no metadata rung succeeds; import time is always present.
  override protected def isNullableTimestamp(field: String): Boolean = field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // PostgreSQL orders NULL after every value
  override protected def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.DESC

  override protected def dayBindValue(day: LocalDate): Any = day
