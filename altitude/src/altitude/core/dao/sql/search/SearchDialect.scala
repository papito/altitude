package altitude.core.dao.sql.search

import java.time.LocalDate
import scalasql.Sc
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect
import scalasql.query.Select

import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.util.DescribeRanking
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SearchTerm
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

/**
 * A Describe search's ranked rows as an engine writes them: `rows` selects each asset's ID and cosine distance, in that order;
 * `sources` are the CTEs it reads, which head the statement's own
 */
case class RankedRows(rows: SqlStr, sources: Seq[SqlStr] = Nil)

/**
 * What a search says differently to each engine. The day expression and the secondary sort term are raw fragments on purpose:
 * they must match, character for character, the expression `asset_search_date_taken` is built on, and carry SQLite's planner
 * hint.
 */
trait SearchDialect:

  val dialect: Dialect

  /**
   * The prefix and suffix that make a timestamp column's calendar day: the one spelling the engine's date indexes are built on,
   * which [[day]] and [[dayOf]] both render
   */
  val dayWrapper: (String, String)

  /** The calendar day a date grouping orders, compares and counts by */
  def day(asset: AssetRow[Expr], field: String): Expr[Option[LocalDate]] =
    val column = Columns.required(AssetRow, asset, field, dialect)
    Expr[Option[LocalDate]](implicit ctx => sql"${SqlStr.raw(dayWrapper._1)}$column${SqlStr.raw(dayWrapper._2)}")

  /** The same day expression over a column named in a hand-written statement (`BaseDao.dayOf` renders through it) */
  def dayOf(column: String): String = s"${dayWrapper._1}$column${dayWrapper._2}"

  /**
   * Whether a search document has the words of any of the term's readings, consecutively and in order, the last one as a prefix
   * unless the term is a phrase. Exclusion is the caller's to apply.
   */
  def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean]

  /** The ORDER BY term for a column sort within a group. Engines may decorate it to steer their planner. */
  def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?]

  /** An asset in none of the rows of `relation`, a CTE of asset IDs: an excluded term's anti-join, as the engine plans one well */
  def excludes(assetId: Expr[String], relation: String): Expr[Boolean]

  /**
   * The assets of `matching` whose Scene embedding is within the ranking's cosine `maxDistance` of its `embedding`, with that
   * distance, the nearest `limit` at most. Ranked exactly, which every engine can, every matching embedding is compared (by
   * distance, then ID), at the cost of the matching assets. An engine with a vector index reads a ranking marked
   * `DescribeRanking.throughIndex` through it.
   */
  def ranked(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows

  /** Whether the engine's schema lets this timestamp column be null */
  def isNullableTimestamp(field: String): Boolean

  /** Where the engine natively places nulls for this direction; the cursor comparison must agree with the ORDER BY */
  def nullsFirst(direction: SortDirection): Boolean

  /** Reads a sort key exactly as the engine stores it, and binds it back the same way for a cursor comparison */
  def sortValueMapper: TypeMapper[SortValue]
