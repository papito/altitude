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

  // Capture time is a wall-clock `timestamp` with no zone, so its calendar day is a plain cast
  override protected def dayExpression(groupBy: GroupBy): String = s"$tableName.${groupBy.field}::date"

  override protected def secondarySortExpression(sort: SearchSort, grouping: SearchGrouping): String =
    s"$tableName.${sort.field}"

  // Capture time is the only nullable timestamp: it is unknown when no metadata rung succeeds.
  override protected def isNullableTimestamp(field: String): Boolean = field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // PostgreSQL orders NULL after every value
  override protected def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.DESC

  override protected def dayBindValue(day: LocalDate): Any = day
