package altitude.core.dao.sql.search

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.ZoneOffset
import scalasql.Sc
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.core.WithSqlExpr
import scalasql.dialects.Dialect
import scalasql.query.Select

import altitude.core.FieldConst
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.SceneModel
import altitude.core.util.DescribeRanking
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SearchTerm
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

object SqliteSearchDialect extends SearchDialect:
  override val dialect: Dialect = AltitudeSqliteDialect

  import dialect.*

  // The stored text is already the camera's wall-clock time: no modifier
  override val dayWrapper: (String, String) = ("date(", ")")

  /**
   * `search_document_fts`'s `rowid` is the document's declared `id`, which is not in the row class (PostgreSQL's table has no
   * such column), so it is named directly: in the enclosing subquery it resolves to `search_document`'s.
   *
   * The term's words are letters and digits only, so they are safe to join into one quoted FTS5 phrase; a trailing `*` makes the
   * last word a prefix, and a term's readings are joined by `OR` (`"mc donald" * OR "mcdonald" *`).
   */
  override def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean] =
    val ftsQuery = term.variants.map(_.mkString("\"", " ", "\"") + (if term.isPhrase then "" else " *")).mkString(" OR ")
    Expr[Boolean](implicit ctx => sql"id IN (SELECT rowid FROM search_document_fts WHERE search_document_fts MATCH $ftsQuery)")

  /**
   * A sort term SQLite can match against an index tempts its planner away from the grouping day index, which then has to sort
   * every matching row; the unary plus keeps any term but the grouping date from being matched. A Location or Sequence grouping
   * has no such index, so every sort term of one gets the plus.
   */
  override def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?] =
    if grouping.by.dateField.contains(sort.field) then column else Expr[Any](implicit ctx => sql"+$column")

  // NOT IN over a CTE builds an index of the set once; NOT EXISTS would scan the CTE, which has no index, for every asset
  override def excludes(assetId: Expr[String], relation: String): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"($assetId NOT IN (SELECT asset_id FROM ${SqlStr.raw(relation)}))")

  /**
   * `vector_full_scan` is sqlite-vector's exact scan of every repository's SigLIP 2 embeddings in `asset`, yielding rowid and
   * cosine distance (rows without an embedding are absent). The matching assets join this `scanned` CTE by rowid, so the search's
   * filters, cutoff, order and limit apply to the scan's output. The column is registered with the extension on every new
   * connection (`TransactionManager.setUpSqliteConnection`). SQLite has no vector index, so the scan reads every embedding
   * whatever the filters leave.
   */
  override def ranked(matching: Select[AssetRow[Expr], AssetRow[Sc]], ranking: DescribeRanking): RankedRows =
    val rowid = SearchQueries.assetColumn(matching, "rowid")
    val scannedAssets =
      matching.join(ScannedRow)((_, scanned) => Expr[Boolean](implicit ctx => sql"$rowid = ${scanned.assetRowid}"))
    val (asset, scanned) = WithSqlExpr.get(scannedAssets)
    val nearest = SearchQueries.sliced(
      this,
      scannedAssets
        .filter(_ => Expr[Boolean](implicit ctx => sql"${scanned.distance} <= ${ranking.maxDistance}"))
        .map(_ => (asset.id, scanned.distance)),
      sql"${scanned.distance}, ${asset.id}",
      SqlStr.raw(s" LIMIT ${ranking.limit}")
    )
    val vector = scalasql.Bytes(SqliteOverrides.vectorBytes(ranking.embedding.values))
    RankedRows(
      rows = nearest,
      sources = Seq(sql"""scanned (asset_rowid, distance) AS (
        SELECT rowid, distance FROM vector_full_scan('asset', '${SqlStr.raw(SceneModel.Siglip2.column)}', $vector))""")
    )

  // Capture time is null when no metadata rung succeeds; import time only on legacy rows, and only ever as a sort column
  override def isNullableTimestamp(field: String): Boolean =
    field == FieldConst.CREATED_AT || field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // SQLite orders NULL before every value
  override def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.ASC

  // Both are stored as text, read as it is
  override protected def readsTimestampAs(field: String, value: SortValue): Boolean = value.isInstanceOf[SortValue.Text]

  /** Timestamps stay in their stored text form so a cursor compares them exactly as the column stores them */
  override val sortValueMapper: TypeMapper[SortValue] = new TypeMapper[SortValue]:
    def jdbcType: JDBCType = JDBCType.OTHER

    def get(r: ResultSet, idx: Int): SortValue = r.getObject(idx) match
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
      // A SQLite cursor never carries a typed timestamp (the engine hands them back as text), but bind one as stored anyway
      case SortValue.LocalTimestamp(dateTime) => r.setString(idx, dateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.UtcInstant(instant) =>
        r.setString(idx, instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")
