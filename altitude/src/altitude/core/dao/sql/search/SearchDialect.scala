package altitude.core.dao.sql.search

import java.time.LocalDate
import scalasql.core.Expr
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect

import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.util.GroupBy
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

/**
 * What a search has to say differently to each engine.
 *
 * These are the hooks the two `AssetSearchQueryBuilder`s used to carry, now written against typed columns. The day expression and
 * the secondary sort term are still emitted as raw fragments on purpose: they have to match, character for character, the
 * expression the engine's `asset_search_date_taken` index is built on, and the planner hint SQLite needs.
 */
trait SearchDialect:

  /** The ScalaSql dialect the typed halves of a search are written against */
  val dialect: Dialect

  /** The calendar-day expression a grouped search orders, compares and counts by; it must match the engine's date index */
  def day(asset: AssetRow[Expr], groupBy: GroupBy): Expr[Option[LocalDate]]

  /** The engine's full-text predicate over one search document */
  def textMatch(document: SearchDocumentRow[Expr], text: String): Expr[Boolean]

  /** The ORDER BY term for the sort within a day. Engines may decorate it to steer their planner. */
  def secondarySort(asset: AssetRow[Expr], sort: SearchSort, grouping: SearchGrouping): Expr[?]

  /** Whether the engine's schema lets this timestamp column be null */
  def isNullableTimestamp(field: String): Boolean

  /** Where the engine natively places nulls for this direction; the cursor comparison must agree with the ORDER BY */
  def nullsFirst(direction: SortDirection): Boolean

  /** Reads a sort key exactly as the engine stores it, and binds it back the same way for a cursor comparison */
  def sortValueMapper: TypeMapper[SortValue]
