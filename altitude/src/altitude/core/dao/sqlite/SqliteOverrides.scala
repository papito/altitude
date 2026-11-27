package altitude.core.dao.sqlite

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import altitude.core.models.SceneEmbedding

object SqliteOverrides:
  /**
   * The stored DATETIME text format: wall-clock text, camera-local for capture times and UTC for import times. A fraction of a
   * second is written only when there is one, with no trailing zeros, so a whole second is `yyyy-MM-dd HH:mm:ss` and `date()`
   * reads both. Every writer, test helpers included, must write this canonical form: hand-written statements compare the stored
   * text (`BETWEEN` on a capture-time window), which orders as the times do only while no row carries trailing zeros or a `.0`.
   */
  val DATETIME_FORMATTER: DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .optionalEnd()
    .toFormatter

  /** A vector as its BLOB: raw little-endian float32 values, the layout `vector_as_f32` stores and sqlite-vector scans */
  def vectorBytes(values: IArray[Float]): Array[Byte] =
    val buffer = ByteBuffer.allocate(values.length * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val floats = buffer.asFloatBuffer
    values.foreach(floats.put)
    buffer.array

trait SqliteOverrides:
  this: BaseDao[?] =>

  override protected val dialect: Dialect = AltitudeSqliteDialect

  override protected def jsonFunc = "?"

  override protected def jsonText(column: String, key: String): String = s"json_extract($column, '$$.$key')"

  // A number, as a boolean column stores it: SQLite takes a partial index whose predicate is on a flag only for a bound value
  // equal to the predicate's, which the text "0" never is
  override protected def nativeBool(value: Boolean): Any =
    if value then 1 else 0

  override protected val vectorPlaceholder: String = "?"

  override protected def vectorBind(values: IArray[Float]): Any = SqliteOverrides.vectorBytes(values)

  override protected def getVectorField(value: AnyRef): Option[SceneEmbedding] = Option(value).map {
    case bytes: Array[Byte] =>
      val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer
      val values = new Array[Float](floats.remaining)
      floats.get(values)
      SceneEmbedding(IArray.unsafeFromArray(values))
    case other => throw IllegalArgumentException(s"Invalid type for vector field: $other")
  }

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

  // A hand-written read's flag (`makeModel`). SQLite has no BOOLEAN type: a boolean column reads back as an INTEGER, 0 or 1.
  // Anything else, a NULL included, is no flag the app wrote, and fails the read rather than read as false.
  override protected def getBooleanField(value: AnyRef): Boolean = value match
    case b: java.lang.Boolean => b.booleanValue
    case i: java.lang.Integer if i.intValue == 0 || i.intValue == 1 => i.intValue == 1
    case other => throw IllegalArgumentException(s"Invalid value for a boolean field: $other")

  override protected def getNextVal(tableName: String): AnyRef =
    val sql = s"INSERT INTO $tableName DEFAULT VALUES RETURNING id"
    val res = executeAndGetOne(sql, List())
    res("id")

  override val forUpdate: String = "" // SQLite has no row-level locking

  override val forKeyShare: String = ""

  override val forNoKeyUpdate: String = ""

  override protected def advisoryLock(key: String): Unit = () // The one write connection serializes the writers

  override protected val inIdSet: String = "IN (SELECT value FROM json_each(?))"

  override protected def idSet(ids: Set[String]): Any = ujson.write(ids)
