package altitude.core.dao.sqlite

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

import altitude.core.dao.jdbc.BaseDao
import altitude.core.util.SortValue

object SqliteOverrides:
  /** The stored DATETIME text format. Timestamps are wall-clock text: camera-local for capture times, UTC for import times. */
  val DATETIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  /** Reads the stored format, tolerating a fractional second on rows written from a java.sql.Timestamp */
  val DATETIME_PARSER: DateTimeFormatter = new DateTimeFormatterBuilder()
    .append(DATETIME_FORMATTER)
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .optionalEnd()
    .toFormatter

trait SqliteOverrides:
  this: BaseDao[?] =>

  override protected def jsonFunc = "?"

  override protected def nativeBool(value: Boolean): Any =
    if value then "1" else "0"

  override protected def nativeLocalDateTime(value: LocalDateTime): Any =
    value.format(SqliteOverrides.DATETIME_FORMATTER)

  override protected def nativeUtcTimestamp(value: OffsetDateTime): Any =
    value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)

  // Parsed as the wall-clock time it spells; a JVM-zone round trip would shift a time inside the server's DST gap
  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] =
    if value.isEmpty || value.get == null then return None

    Some(LocalDateTime.parse(value.get.asInstanceOf[String], SqliteOverrides.DATETIME_PARSER))

  // date() returns ISO text; it is never converted through a JVM zone
  override protected def getDateField(value: AnyRef): LocalDate = LocalDate.parse(value.asInstanceOf[String])

  // Timestamps stay in their stored text form so a cursor compares them exactly as the column stores them
  override protected def getSortValueField(value: AnyRef): SortValue = value match
    case null => SortValue.Null
    case text: String => SortValue.Text(text)
    case number: java.lang.Number => SortValue.Num(number.longValue)
    case other => throw IllegalArgumentException(s"Unsupported sort value: $other")

  def count(recs: List[Map[String, AnyRef]]): Int = if recs.nonEmpty then recs.head("total").asInstanceOf[Int] else 0

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
