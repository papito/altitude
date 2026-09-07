package altitude.core.dao.sqlite.querybuilder

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
    if searchQuery.isText then ClauseComponents(elements = List("body MATCH ?"), bindVals = List(searchQuery.text.get))
    else ClauseComponents()

  // The stored text is already wall-clock time in the right zone (camera-local or UTC): no modifier
  override protected def dayExpression(groupBy: GroupBy): String = s"date($tableName.${groupBy.field})"

  /**
   * Sorting by the other date source's column tempts SQLite's planner into that source's index, which then sorts every matching
   * row; the unary plus keeps the term from being matched against any index, leaving the grouping day index in charge.
   */
  override protected def secondarySortExpression(sort: SearchSort, grouping: SearchGrouping): String =
    if sort.field == grouping.by.field then s"$tableName.${sort.field}" else s"+$tableName.${sort.field}"

  override protected def isNullableTimestamp(field: String): Boolean = field == FieldConst.CREATED_AT

  // SQLite orders NULL before every value
  override protected def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.ASC

  override protected def dayBindValue(day: LocalDate): Any = day.toString
