package altitude.core.dao.sql.search

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.OffsetDateTime
import scalasql.Sc
import scalasql.core.Context
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect
import scalasql.query.Select

import altitude.core.FieldConst
import altitude.core.dao.postgres.PostgresOverrides
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.models.SceneModel
import altitude.core.util.DescribeRanking
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SearchTerm
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

object PostgresSearchDialect extends SearchDialect:
  override val dialect: Dialect = AltitudePostgresDialect

  import dialect.*

  // Capture time is a wall-clock `timestamp` with no zone, so its calendar day is a plain cast
  override val dayWrapper: (String, String) = ("", "::date")

  /**
   * `tsv` is not in the shared row class (SQLite's table has none), so it is named directly; the subquery it lands in reads
   * `search_document` innermost and no asset column is called `tsv`, so it is unambiguous. The query uses the `simple`
   * configuration the vector is built with.
   *
   * A term's words are letters and digits only, so they are safe to join into tsquery syntax: `<->` is "directly followed by",
   * `:*` makes the last word a prefix (`img <-> 12:*`), and readings are alternatives joined by `|`, which binds looser than
   * `<->` (`mc <-> donald:* | mcdonald:*`).
   */
  override def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean] =
    val tsQuery = term.variants.map(_.mkString(" <-> ") + (if term.isPhrase then "" else ":*")).mkString(" | ")
    Expr[Boolean](implicit ctx => sql"tsv @@ to_tsquery('simple', $tsQuery)")

  override def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?] = column

  // NOT EXISTS is planned as a hash anti-join; NOT IN over a subquery is a SubPlan probed for every asset
  override def excludes(assetId: Expr[String], relation: String): Expr[Boolean] =
    val cte = SqlStr.raw(relation)
    Expr[Boolean](implicit ctx => sql"(NOT EXISTS (SELECT 1 FROM $cte WHERE $cte.asset_id = $assetId))")

  override def ranked(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows =
    if ranking.throughIndex then rankedByIndex(matching, ranking) else rankedExactly(matching, ranking)

  /** The half-precision type `asset_describe` indexes the Scene embedding as */
  private val HALF_PRECISION: SqlStr = SqlStr.raw(s"halfvec(${SceneModel.Siglip2.dimensions})")

  /** Each match with the exact, full-precision cosine distance of its Scene embedding from `literal`: what both rankings read */
  private def distances(
      matches: Select[AssetRow[Expr], AssetRow[Sc]],
      literal: String): Select[(Expr[String], Expr[Double]), (String, Double)] =
    val column = SearchQueries.assetColumn(matches, SceneModel.Siglip2.column)
    matches.map(asset => (asset.id, Expr[Double](implicit ctx => sql"$column <=> $literal::vector")))

  /**
   * Ordering by the `vector` column, not the half-precision cast `asset_describe` is built on, keeps the planner off that index:
   * it reads the matches through the filters' own indexes and sorts. `OFFSET 0` keeps the inner select from being flattened into
   * the outer one, which would compute each distance twice, for the cutoff and for the order.
   */
  def rankedExactly(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows =
    val embedded = distances(SearchQueries.embedded(matching), PostgresOverrides.vectorLiteral(ranking.embedding.values))
    RankedRows(sql"""
      SELECT embedded.id, embedded.distance
        FROM (${Db.render(embedded, dialect).withCompleteQuery(false)} OFFSET 0) AS embedded (id, distance)
       WHERE embedded.distance <= ${ranking.maxDistance}
       ORDER BY embedded.distance, embedded.id
       LIMIT ${SqlStr.raw(ranking.limit.toString)}""")

  /**
   * The ranked rows through `asset_describe`, the half-precision HNSW index the inner order matches, approximate as the index is.
   * The search's filters are in the inner `WHERE`, so they apply as the index is read, and the transaction
   * (`TransactionManager.withVectorSearch`) keeps the scan going until `limit` rows pass or the index ends. The cutoff is applied
   * outside, on the exact distance: inside, a description with nothing near enough would send the index looking for rows that are
   * not there. Where the planner scans the table instead, an asset with no embedding has a NULL distance, which the cutoff drops.
   */
  def rankedByIndex(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows =
    val literal = PostgresOverrides.vectorLiteral(ranking.embedding.values)
    val column = SearchQueries.assetColumn(matching, SceneModel.Siglip2.column)
    def byHalfPrecision(using Context): SqlStr = sql"$column::$HALF_PRECISION <=> $literal::$HALF_PRECISION"
    val nearest =
      SearchQueries.sliced(this, distances(matching, literal), byHalfPrecision, SqlStr.raw(s" LIMIT ${ranking.limit}"))
    RankedRows(sql"""
      SELECT nearest.id, nearest.distance
        FROM ($nearest) AS nearest (id, distance)
       WHERE nearest.distance <= ${ranking.maxDistance}""")

  // Capture time is the only nullable timestamp: it is unknown when no metadata rung succeeds.
  override def isNullableTimestamp(field: String): Boolean = field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // PostgreSQL orders NULL after every value
  override def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.DESC

  override val sortValueMapper: TypeMapper[SortValue] = new TypeMapper[SortValue]:
    def jdbcType: JDBCType = JDBCType.OTHER

    /**
     * pgJDBC's default `java.sql` temporal types represent instants in the JVM zone, so the column's own type decides how it is
     * read, as in `PostgresOverrides.javaTimeRowProcessor`
     */
    def get(r: ResultSet, idx: Int): SortValue =
      r.getMetaData.getColumnTypeName(idx) match
        case "timestamp" => wrap(r.getObject(idx, classOf[LocalDateTime]))(SortValue.LocalTimestamp.apply)
        case "timestamptz" => wrap(r.getObject(idx, classOf[OffsetDateTime]))(SortValue.UtcInstant.apply)
        case _ =>
          r.getObject(idx) match
            case null => SortValue.Null
            case text: String => SortValue.Text(text)
            // A similarity; every other number a search sorts by is whole
            case real: java.lang.Double => SortValue.Real(real)
            case number: java.lang.Number => SortValue.Num(number.longValue)
            case other => throw IllegalArgumentException(s"Unsupported sort value: $other")

    def put(r: PreparedStatement, idx: Int, v: SortValue): Unit = v match
      case SortValue.Text(text) => r.setString(idx, text)
      case SortValue.Num(number) => r.setLong(idx, number)
      case SortValue.Real(number) => r.setDouble(idx, number)
      case SortValue.LocalTimestamp(dateTime) => r.setObject(idx, dateTime)
      case SortValue.UtcInstant(instant) => r.setObject(idx, instant)
      case SortValue.Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")

    private def wrap[T](value: T)(f: T => SortValue): SortValue = if value == null then SortValue.Null else f(value)
