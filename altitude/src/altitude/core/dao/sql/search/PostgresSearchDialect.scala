package altitude.core.dao.sql.search

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import scalasql.core.Expr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect

import altitude.core.FieldConst
import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.util.GroupBy
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

object PostgresSearchDialect extends SearchDialect:
  override val dialect: Dialect = AltitudePostgresDialect

  import dialect.*

  // Capture time is a wall-clock `timestamp` with no zone, so its calendar day is a plain cast
  override def day(asset: AssetRow[Expr], groupBy: GroupBy): Expr[Option[LocalDate]] =
    val column = Columns.required(AssetRow, asset, groupBy.field, dialect)
    Expr[Option[LocalDate]](implicit ctx => sql"$column::date")

  /**
   * `tsv` is not part of the shared row class - SQLite's fts4 table has no such column - so it is named directly. The subquery
   * this lands in has `search_document` as its only table and no asset column is called `tsv`, so the reference is unambiguous.
   */
  override def textMatch(document: SearchDocumentRow[Expr], text: String): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"tsv @@ to_tsquery($text)")

  override def secondarySort(asset: AssetRow[Expr], sort: SearchSort, grouping: SearchGrouping): Expr[?] =
    Columns.required(AssetRow, asset, sort.field, dialect)

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
            case number: java.lang.Number => SortValue.Num(number.longValue)
            case other => throw IllegalArgumentException(s"Unsupported sort value: $other")

    def put(r: PreparedStatement, idx: Int, v: SortValue): Unit = v match
      case SortValue.Text(text) => r.setString(idx, text)
      case SortValue.Num(number) => r.setLong(idx, number)
      case SortValue.LocalTimestamp(dateTime) => r.setObject(idx, dateTime)
      case SortValue.UtcInstant(instant) => r.setObject(idx, instant)
      case SortValue.Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")

    private def wrap[T](value: T)(f: T => SortValue): SortValue = if value == null then SortValue.Null else f(value)
