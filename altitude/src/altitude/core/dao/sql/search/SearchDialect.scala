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
 * The ranked rows of a Describe search as an engine writes them ([[SearchDialect.ranked]]): `rows` is the select of each ranked
 * asset's ID and its cosine distance from the description, in that order, and `sources` the CTEs that select reads, which head
 * the statement's own.
 */
case class RankedRows(rows: SqlStr, sources: Seq[SqlStr] = Nil)

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

  /**
   * What the engine wraps a timestamp column in for its calendar day, as a prefix and a suffix: the one spelling of the
   * expression the engine's date indexes are built on, which [[day]] and [[dayOf]] both render
   */
  val dayWrapper: (String, String)

  /**
   * The calendar day of a timestamp column, which a date grouping orders, compares and counts by; it must match the engine's date
   * index
   */
  def day(asset: AssetRow[Expr], field: String): Expr[Option[LocalDate]] =
    val column = Columns.required(AssetRow, asset, field, dialect)
    Expr[Option[LocalDate]](implicit ctx => sql"${SqlStr.raw(dayWrapper._1)}$column${SqlStr.raw(dayWrapper._2)}")

  /** The same day expression over a column named in a hand-written statement (`BaseDao.dayOf` renders through it) */
  def dayOf(column: String): String = s"${dayWrapper._1}$column${dayWrapper._2}"

  /**
   * The engine's full-text predicate over one search document: whether it has the words of any of the term's readings,
   * consecutively and in order, the last one as a prefix unless the term is a phrase. Exclusion is the caller's to apply.
   */
  def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean]

  /** The ORDER BY term for a column sort within a group. Engines may decorate it to steer their planner. */
  def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?]

  /**
   * An asset that is in none of a relation's rows, the relation being a CTE of asset IDs: the anti-join of a group of one
   * excluded term, written the way the engine plans one well
   */
  def excludes(assetId: Expr[String], relation: String): Expr[Boolean]

  /**
   * The ranked rows of a Describe search: the assets of `matching`, the relation of the search's filters, that carry a SigLIP 2
   * Scene embedding no further than the ranking's `maxDistance`, a cosine distance, from its `embedding`, a description's, each
   * with that distance, the nearest `limit` of them at most. Read exactly, every matching embedding is read and compared, by
   * distance and then ID, so the result is exact and its cost is the matching assets': the one way of ranking every engine has.
   * An engine with a vector index reads a ranking that asks for it (`DescribeRanking.throughIndex`, which its DAO decides)
   * through that index (`PostgresSearchDialect.rankedByIndex`); an engine with none reads every ranking exactly.
   */
  def ranked(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows

  /** Whether the engine's schema lets this timestamp column be null */
  def isNullableTimestamp(field: String): Boolean

  /** Where the engine natively places nulls for this direction; the cursor comparison must agree with the ORDER BY */
  def nullsFirst(direction: SortDirection): Boolean

  /** Reads a sort key exactly as the engine stores it, and binds it back the same way for a cursor comparison */
  def sortValueMapper: TypeMapper[SortValue]
