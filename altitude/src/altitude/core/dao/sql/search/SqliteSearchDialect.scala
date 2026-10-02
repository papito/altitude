package altitude.core.dao.sql.search

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect

import altitude.core.FieldConst
import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SearchTerm
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

object SqliteSearchDialect extends SearchDialect:
  override val dialect: Dialect = AltitudeSqliteDialect

  import dialect.*

  // The stored text is already the camera's wall-clock time: no modifier
  override def day(asset: AssetRow[Expr], field: String): Expr[Option[LocalDate]] =
    val column = Columns.required(AssetRow, asset, field, dialect)
    Expr[Option[LocalDate]](implicit ctx => sql"date($column)")

  /**
   * The full-text index is `search_document_fts`, which shares its `rowid` with the document. `rowid` is not part of the row
   * class, so it is named directly: the subquery this lands in has `search_document` as its only table, and inside the index
   * subquery the name is the index's own.
   *
   * The term's words are letters and digits only, so they are safe to join into one quoted FTS5 string, which matches as the
   * phrase of its words; a `*` after the string makes its last word a prefix (`"img 12" *`). The readings of a term are
   * alternatives joined by `OR` (`"mc donald" * OR "mcdonald" *`).
   */
  override def textMatch(document: SearchDocumentRow[Expr], term: SearchTerm): Expr[Boolean] =
    val ftsQuery = term.variants.map(_.mkString("\"", " ", "\"") + (if term.isPhrase then "" else " *")).mkString(" OR ")
    Expr[Boolean](implicit ctx => sql"rowid IN (SELECT rowid FROM search_document_fts WHERE search_document_fts MATCH $ftsQuery)")

  /**
   * A sort term SQLite can match against an index tempts its planner away from the grouping day index, which then has to sort
   * every matching row; the unary plus keeps any term other than the grouping date from being matched, leaving the day index in
   * charge. A Location grouping has no such index, and every sort term of one gets the plus.
   */
  override def secondarySort(asset: AssetRow[Expr], sort: SearchSort, grouping: SearchGrouping): Expr[?] =
    val column = Columns.required(AssetRow, asset, sort.field, dialect)
    if grouping.by.dateField.contains(sort.field) then column else Expr[Any](implicit ctx => sql"+$column")

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
      // A SQLite cursor never carries a typed timestamp - the engine hands them back as text - but bind one as stored anyway
      case SortValue.LocalTimestamp(dateTime) => r.setString(idx, dateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.UtcInstant(instant) =>
        r.setString(idx, instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case SortValue.Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")
