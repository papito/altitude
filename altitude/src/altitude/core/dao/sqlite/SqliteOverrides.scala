package altitude.core.dao.sqlite

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import scalasql.dialects.Dialect

import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.search.SqliteSearchDialect

object SqliteOverrides:
  /**
   * The stored DATETIME text format, written and read. Timestamps are wall-clock text: camera-local for capture times, UTC for
   * import times. A fraction of a second is written only when there is one (a burst frame's subsecond capture time, the clock's
   * microseconds on an import time, as PostgreSQL's TIMESTAMPTZ keeps them), with no trailing zeros, so a whole second is
   * `yyyy-MM-dd HH:mm:ss` as every existing row is, and `date()` reads both. Every writer, the tests' helpers included, must
   * write this canonical form: a hand-written statement compares the stored text (`BETWEEN` on a capture-time window), which
   * orders as the times do only while no row carries trailing zeros or a `.0`.
   */
  val DATETIME_FORMATTER: DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .optionalEnd()
    .toFormatter

trait SqliteOverrides:
  this: BaseDao[?] =>

  override protected val dialect: Dialect = AltitudeSqliteDialect

  // An instant is stored as its UTC wall clock and handed back verbatim, as `getDateTimeField` does for the raw paths
  override protected def toLocalDateTime(value: OffsetDateTime): LocalDateTime = value.toLocalDateTime

  override protected def jsonFunc = "?"

  override protected def nativeBool(value: Boolean): Any =
    if value then "1" else "0"

  override protected def nativeLocalDateTime(value: LocalDateTime): Any =
    value.format(SqliteOverrides.DATETIME_FORMATTER)

  override protected def nativeUtcTimestamp(value: OffsetDateTime): Any =
    value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)

  // ISO text, which is what `date()` of a stored timestamp yields
  override protected def nativeLocalDate(value: LocalDate): Any = value.toString

  override protected def dayOf(column: String): String = SqliteSearchDialect.dayOf(column)

  // Parsed as the wall-clock time it spells; a JVM-zone round trip would shift a time inside the server's DST gap
  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] =
    if value.isEmpty || value.get == null then return None

    Some(LocalDateTime.parse(value.get.asInstanceOf[String], SqliteOverrides.DATETIME_FORMATTER))

  // SQLITE does not have a BOOLEAN type, so we use an INTEGER type instead and "fix it in post"
  override protected def getBooleanField(value: AnyRef): Boolean = value match
    case b: java.lang.Boolean => b.booleanValue
    case i: java.lang.Integer =>
      i.intValue match
        case 0 => false
        case 1 => true
        case _ => false
    case _ => false

  override protected def getNextVal(tableName: String): AnyRef =
    val sql = s"INSERT INTO $tableName DEFAULT VALUES RETURNING id"
    val res = executeAndGetOne(sql, List())
    res("id")

  override val forUpdate: String = "" // SQLITE does not support row-level locking, so no need for "FOR UPDATE"

  override protected val inIdSet: String = "IN (SELECT value FROM json_each(?))"

  override protected def idSet(ids: Set[String]): Any = ujson.write(ids)
