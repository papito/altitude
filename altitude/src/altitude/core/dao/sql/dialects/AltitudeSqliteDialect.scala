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
 * SQLite for this schema.
 *
 * ScalaSql's stock SQLite temporal mappers go through `getObject(idx, classOf[LocalDateTime])`, which is not the format this
 * schema stores: timestamps are `yyyy-MM-dd HH:mm:ss` wall-clock text (camera-local for capture times, UTC for import times) and
 * a calendar day is ISO text. They are read and written through [[SqliteOverrides]]' formatter so a value never round-trips
 * through an instant in the JVM zone.
 */
object AltitudeSqliteDialect extends SqliteDialect:

  /** A wall-clock timestamp: exactly the text the column stores, with no zone attached */
  implicit override def LocalDateTimeType: TypeMapper[LocalDateTime] = localDateTimeType

  /** An instant: stored as its UTC wall clock, so it is read back as that same moment at UTC */
  implicit override def OffsetDateTimeType: TypeMapper[OffsetDateTime] = offsetDateTimeType

  implicit override def LocalDateType: TypeMapper[LocalDate] = localDateType

  /**
   * ScalaSql's own `SqliteDialect.TableOps` resolves its dialect from the library's `SqliteDialect` object rather than from the
   * dialect in scope, so a table built through it would read and write every column with the stock mappers - silently ignoring
   * the ones above. The base `TableOps` threads the dialect it is given, and is otherwise identical.
   */
  implicit override def TableOpsConv[V[_[_]]](t: Table[V]): TableOps[V] = new TableOps(t)(using this)

  private val localDateTimeType: TypeMapper[LocalDateTime] = new TypeMapper[LocalDateTime]:
    def jdbcType: JDBCType = JDBCType.VARCHAR
    override def castTypeString: String = "VARCHAR"

    def get(r: ResultSet, idx: Int): LocalDateTime =
      val text = r.getString(idx)
      if text == null then null else LocalDateTime.parse(text, SqliteOverrides.DATETIME_PARSER)

    def put(r: PreparedStatement, idx: Int, v: LocalDateTime): Unit =
      if v == null then r.setNull(idx, JDBCType.VARCHAR.getVendorTypeNumber)
      else r.setString(idx, v.format(SqliteOverrides.DATETIME_FORMATTER))

  private val offsetDateTimeType: TypeMapper[OffsetDateTime] = new TypeMapper[OffsetDateTime]:
    def jdbcType: JDBCType = JDBCType.VARCHAR
    override def castTypeString: String = "VARCHAR"

    def get(r: ResultSet, idx: Int): OffsetDateTime =
      val text = r.getString(idx)
      if text == null then null
      else LocalDateTime.parse(text, SqliteOverrides.DATETIME_PARSER).atOffset(ZoneOffset.UTC)

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
