package altitude.core.dao.sql.dialects

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scalasql.Table
import scalasql.core.TypeMapper
import scalasql.dialects.SqliteDialect
import scalasql.dialects.TableOps

import altitude.core.dao.sqlite.SqliteOverrides

/**
 * ScalaSql's stock SQLite temporal mappers do not read this schema's format: timestamps are `yyyy-MM-dd HH:mm:ss[.fraction]`
 * wall-clock text (camera-local capture times, UTC import times) and a day is ISO text. They go through [[SqliteOverrides]]'
 * formatter, so a value never round-trips through an instant in the JVM zone.
 */
object AltitudeSqliteDialect extends SqliteDialect:

  /** A wall-clock timestamp: the stored text, with no zone */
  implicit override def LocalDateTimeType: TypeMapper[LocalDateTime] = localDateTimeType

  /** An instant: stored as its UTC wall clock, read back at UTC */
  implicit override def OffsetDateTimeType: TypeMapper[OffsetDateTime] = offsetDateTimeType

  implicit override def LocalDateType: TypeMapper[LocalDate] = localDateType

  /**
   * ScalaSql's `SqliteDialect.TableOps` takes its dialect from the library's `SqliteDialect` object, not the one in scope, so it
   * would silently use the stock mappers instead of the ones above. The base `TableOps` threads the dialect it is given.
   */
  implicit override def TableOpsConv[V[_[_]]](t: Table[V]): TableOps[V] = new TableOps(t)(using this)

  private val localDateTimeType: TypeMapper[LocalDateTime] = new TypeMapper[LocalDateTime]:
    def jdbcType: JDBCType = JDBCType.VARCHAR
    override def castTypeString: String = "VARCHAR"

    def get(r: ResultSet, idx: Int): LocalDateTime =
      val text = r.getString(idx)
      if text == null then null else LocalDateTime.parse(text, SqliteOverrides.DATETIME_FORMATTER)

    def put(r: PreparedStatement, idx: Int, v: LocalDateTime): Unit =
      if v == null then r.setNull(idx, JDBCType.VARCHAR.getVendorTypeNumber)
      else r.setString(idx, v.format(SqliteOverrides.DATETIME_FORMATTER))

  private val offsetDateTimeType: TypeMapper[OffsetDateTime] = new TypeMapper[OffsetDateTime]:
    def jdbcType: JDBCType = JDBCType.VARCHAR
    override def castTypeString: String = "VARCHAR"

    def get(r: ResultSet, idx: Int): OffsetDateTime =
      val text = r.getString(idx)
      if text == null then null
      else LocalDateTime.parse(text, SqliteOverrides.DATETIME_FORMATTER).atOffset(ZoneOffset.UTC)

    def put(r: PreparedStatement, idx: Int, v: OffsetDateTime): Unit =
      if v == null then r.setNull(idx, JDBCType.VARCHAR.getVendorTypeNumber)
      else r.setString(idx, v.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER))

  private val localDateType: TypeMapper[LocalDate] = new TypeMapper[LocalDate]:
    def jdbcType: JDBCType = JDBCType.VARCHAR
    override def castTypeString: String = "VARCHAR"

    def get(r: ResultSet, idx: Int): LocalDate =
      val text = r.getString(idx)
      if text == null then null else LocalDate.parse(text)

    def put(r: PreparedStatement, idx: Int, v: LocalDate): Unit =
      if v == null then r.setNull(idx, JDBCType.VARCHAR.getVendorTypeNumber) else r.setString(idx, v.toString)
