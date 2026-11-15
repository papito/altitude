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
   * `tsv` is not part of the shared row class - SQLite's table has no such column - so it is named directly. The subquery this
   * lands in reads `search_document` as its innermost table (a source's CTE, the probe, or a probe on one asset's row) and no
   * asset column is called `tsv`, so the reference is unambiguous. The query is read with the `simple` configuration the vector
   * is generated with.
   *
   * The term's words are letters and digits only, so they are safe to join into tsquery syntax: `<->` is "directly followed by",
   * and `:*` makes the last word a prefix (`img <-> 12:*`). The readings of a term are alternatives joined by `|`, which binds
   * looser than `<->` (`mc <-> donald:* | mcdonald:*`).
   */
  override def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean] =
    val tsQuery = term.variants.map(_.mkString(" <-> ") + (if term.isPhrase then "" else ":*")).mkString(" | ")
    Expr[Boolean](implicit ctx => sql"tsv @@ to_tsquery('simple', $tsQuery)")

  override def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?] = column

  // NOT EXISTS is planned as a hash anti-join; NOT IN over a subquery is a SubPlan probed for every asset
  override def excludes(assetId: Expr[String], relation: String): Expr[Boolean] =
    val cte = SqlStr.raw(relation)
    Expr[Boolean](implicit ctx => sql"(NOT EXISTS (SELECT 1 FROM $cte WHERE $cte.asset_id = $assetId))")

  /** The ranked rows as the ranking asks for them: through the vector index ([[rankedByIndex]]), or exactly ([[rankedExactly]]) */
  override def ranked(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows =
    if ranking.throughIndex then rankedByIndex(matching, ranking) else rankedExactly(matching, ranking)

  /** The type the `asset_describe` index holds a SigLIP 2 Scene embedding as: the column's vector at half precision */
  private val HALF_PRECISION: SqlStr = SqlStr.raw(s"halfvec(${SceneModel.Siglip2.dimensions})")

  /**
   * The assets of `matches`, each with the exact cosine distance of its SigLIP 2 Scene embedding from the description's, whose
   * pgvector literal is `literal`, at the column's full precision: what both ways of ranking read
   */
  private def distances(
      matches: Select[AssetRow[Expr], AssetRow[Sc]],
      literal: String): Select[(Expr[String], Expr[Double]), (String, Double)] =
    val column = SearchQueries.assetColumn(matches, SceneModel.Siglip2.column)
    matches.map(asset => (asset.id, Expr[Double](implicit ctx => sql"$column <=> $literal::vector")))

  /**
   * The matching assets that carry an embedding, each with its exact distance, then the cutoff, the order and the limit over
   * those distances. The order is over the `vector` column itself and not the half-precision cast `asset_describe` is built on,
   * so the planner cannot answer it from that index: it reads the matching assets through the filters' own indexes and sorts.
   * `OFFSET 0` keeps it from flattening the inner select into the outer one, where each distance would be computed once for the
   * cutoff and again for the order.
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
   * The same ranked rows read through the vector index, PostgreSQL's alone, for the searches that leave too many assets to
   * compare one by one (`postgres.SearchDao` chooses): the index hands out the embeddings nearest the description's and the
   * filters of `matching` are applied to each as it does, until the ranking's `limit` of them pass. The result is approximate, as
   * the index is.
   *
   * The candidates come from `asset_describe`, the HNSW index over the SigLIP 2 Scene embeddings at half precision, which the
   * inner order is written to match. The filters of the search are in the inner select's `WHERE`, so they are applied as the
   * index is read: the transaction is set up for a vector search of `limit` rows (`TransactionManager.withVectorSearch`), so the
   * scan goes on until that many rows pass them, or the index ends. Each candidate's distance is the exact one, at full
   * precision, and the cutoff is applied to it outside: inside, a description with nothing near enough would send the index
   * looking for rows that are not there. An asset with no embedding is not in the index, and where the planner scans the table
   * instead, over a few rows, its distance is NULL, which the cutoff drops.
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
     * pgJDBC hands temporal columns out as `java.sql` types by default, which represent instants in the JVM zone. The column's
     * own type decides how it is read, exactly as `PostgresOverrides.javaTimeRowProcessor` does for the raw paths.
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
