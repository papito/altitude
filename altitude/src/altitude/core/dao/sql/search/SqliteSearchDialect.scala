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
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel
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
   * The full-text index is `search_document_fts`, whose `rowid` is the document's declared `id`. `id` is not part of the row
   * class (PostgreSQL's table has no such column), so it is named directly: the subquery this lands in reads `search_document` as
   * its innermost table, so the name is the document's, and inside the index subquery `rowid` is the index's own.
   *
   * The term's words are letters and digits only, so they are safe to join into one quoted FTS5 string, which matches as the
   * phrase of its words; a `*` after the string makes its last word a prefix (`"img 12" *`). The readings of a term are
   * alternatives joined by `OR` (`"mc donald" * OR "mcdonald" *`).
   */
  override def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean] =
    val ftsQuery = term.variants.map(_.mkString("\"", " ", "\"") + (if term.isPhrase then "" else " *")).mkString(" OR ")
    Expr[Boolean](implicit ctx => sql"id IN (SELECT rowid FROM search_document_fts WHERE search_document_fts MATCH $ftsQuery)")

  /**
   * A sort term SQLite can match against an index tempts its planner away from the grouping day index, which then has to sort
   * every matching row; the unary plus keeps any term other than the grouping date from being matched, leaving the day index in
   * charge. A Location or a Sequence grouping has no such index, and every sort term of one gets the plus.
   */
  override def secondarySort(column: Expr[?], sort: SearchSort, grouping: SearchGrouping): Expr[?] =
    if grouping.by.dateField.contains(sort.field) then column else Expr[Any](implicit ctx => sql"+$column")

  // NOT IN over a CTE builds an index of the set once; NOT EXISTS would scan the CTE, which has no index, for every asset
  override def excludes(assetId: Expr[String], relation: String): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"($assetId NOT IN (SELECT asset_id FROM ${SqlStr.raw(relation)}))")

  /**
   * `vector_full_scan` is sqlite-vector's exact scan of the SigLIP 2 Scene embeddings of the `asset` table, every repository's
   * rows, each handed out with its rowid and its cosine distance; a row with no embedding is not among them. It is the `scanned`
   * CTE ([[ScannedRow]]), which the matching assets are joined to by their rowid, so the filters of the search, the cutoff, the
   * order and the limit are applied to what the scan hands out. The column is registered with the extension on every connection
   * as it is opened (`TransactionManager.setUpSqliteConnection`), and the description's vector is bound as the column stores one,
   * its raw float32 values.
   */
  override def rankedExactly(
      matching: Select[AssetRow[Expr], AssetRow[Sc]],
      embedding: SceneEmbedding,
      maxDistance: Double,
      limit: Int): RankedRows =
    val rowid = SearchQueries.assetColumn(matching, "rowid")
    val scannedAssets =
      matching.join(ScannedRow)((_, scanned) => Expr[Boolean](implicit ctx => sql"$rowid = ${scanned.assetRowid}"))
    val (asset, scanned) = WithSqlExpr.get(scannedAssets)
    val nearest = SearchQueries.sliced(
      this,
      scannedAssets
        .filter(_ => Expr[Boolean](implicit ctx => sql"${scanned.distance} <= $maxDistance"))
        .map(_ => (asset.id, scanned.distance)),
      sql"${scanned.distance}, ${asset.id}",
      SqlStr.raw(s" LIMIT $limit")
    )
    val vector = scalasql.Bytes(SqliteOverrides.vectorBytes(embedding))
    RankedRows(
      rows = nearest,
      sources = Seq(sql"""scanned (asset_rowid, distance) AS (
        SELECT rowid, distance FROM vector_full_scan('asset', '${SqlStr.raw(SceneModel.Siglip2.column)}', $vector))""")
    )

  // SQLite has no vector index: its one scan is exact, and reads every embedding whatever the filters leave
  override def rankedByIndex(
      matching: Select[AssetRow[Expr], AssetRow[Sc]],
      embedding: SceneEmbedding,
      maxDistance: Double,
      limit: Int): RankedRows =
    throw UnsupportedOperationException("SQLite ranks a description exactly: it has no vector index")

  // Capture time is null when no metadata rung succeeds; import time only on legacy rows, and only ever as a sort column
  override def isNullableTimestamp(field: String): Boolean =
    field == FieldConst.CREATED_AT || field == FieldConst.Asset.ORIGINAL_CREATED_AT

  // SQLite orders NULL before every value
  override def nullsFirst(direction: SortDirection): Boolean = direction == SortDirection.ASC

  /** Timestamps stay in their stored text form so a cursor compares them exactly as the column stores them */
  override val sortValueMapper: TypeMapper[SortValue] = new TypeMapper[SortValue]:
    def jdbcType: JDBCType = JDBCType.OTHER

    def get(r: ResultSet, idx: Int): SortValue = r.getObject(idx) match
      case null => SortValue.Null
      case text: String => SortValue.Text(text)
      case number: java.lang.Number => SortValue.Num(number.longValue)
      case other => throw IllegalArgumentException(s"Unsupported sort value: $other")

    def put(r: PreparedStatement, idx: Int, v: SortValue): Unit = v match
      case SortValue.Text(text) => r.setString(idx, text)
      case SortValue.Num(number) => r.setLong(idx, number)
      case SortValue.Real(number) => r.setDouble(idx, number)
      // A SQLite cursor never carries a typed timestamp - the engine hands them back as text - but bind one as stored anyway
      case SortValue.LocalTimestamp(dateTime) => r.setString(idx, dateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.UtcInstant(instant) =>
        r.setString(idx, instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")
