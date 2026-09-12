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

  // The stored text is already the camera's wall-clock time: no modifier
  override protected def dayExpression(groupBy: GroupBy): String = s"date($tableName.${groupBy.field})"

  /**
   * A sort term SQLite can match against an index tempts its planner away from the grouping day index, which then has to sort
   * every matching row; the unary plus keeps any non-grouping term from being matched, leaving the day index in charge.
   */
  override protected def secondarySortExpression(sort: SearchSort, grouping: SearchGrouping): String =
    if sort.field == grouping.by.field then s"$tableName.${sort.field}" else s"+$tableName.${sort.field}"

  // Capture time is null when no metadata rung succeeds; import time only on legacy rows, and only ever as a sort column
  override protected def isNullableTimestamp(field: String): Boolean =
    field == FieldConst.CREATED_AT || field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // SQLite orders NULL before every value
  override protected def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.ASC

  override protected def dayBindValue(day: LocalDate): Any = day.toString
