package altitude.core.dao.postgres

import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.apache.commons.dbutils.BasicRowProcessor
import org.apache.commons.dbutils.RowProcessor
import scalasql.dialects.Dialect

import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.sql.dialects.AltitudePostgresDialect

object PostgresOverrides:
  /**
   * pgJDBC hands temporal columns out as java.sql types by default, which represent instants in the JVM zone: a wall-clock
   * `timestamp` inside the server's DST gap would come back shifted. Read them as the java.time types that carry exactly what the
   * column stores instead.
   */
  private val javaTimeRowProcessor: RowProcessor = new BasicRowProcessor():
    override def toMap(rs: ResultSet): java.util.Map[String, AnyRef] =
      val meta = rs.getMetaData
      val row = new java.util.LinkedHashMap[String, AnyRef]()
      for column <- 1 to meta.getColumnCount do
        val value: AnyRef = meta.getColumnTypeName(column) match
          case "timestamp" => rs.getObject(column, classOf[LocalDateTime])
          case "timestamptz" => rs.getObject(column, classOf[OffsetDateTime])
          case "date" => rs.getObject(column, classOf[LocalDate])
          case _ => rs.getObject(column)
        row.put(meta.getColumnLabel(column), value)
      row

trait PostgresOverrides:
  this: BaseDao[?] =>

  override protected val dialect: Dialect = AltitudePostgresDialect

  // An instant is shown in the JVM zone, as `getDateTimeField` does for the raw paths
  override protected def toLocalDateTime(value: OffsetDateTime): LocalDateTime =
    value.atZoneSameInstant(ZoneId.systemDefault).toLocalDateTime

  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def nativeBool(value: Boolean): Any =
    if value then true else false

  override protected def nativeLocalDateTime(value: LocalDateTime): Any = value

  override protected def nativeUtcTimestamp(value: OffsetDateTime): Any = value

  override protected def rowProcessor: RowProcessor = PostgresOverrides.javaTimeRowProcessor

  // A wall-clock `timestamp` is returned as-is; an instant (`timestamptz`) is shown in the JVM zone, as before
  override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] =
    if value.isEmpty || value.get == null then return None

    value.get match
      case dateTime: LocalDateTime => Some(dateTime)
      case instant: OffsetDateTime => Some(instant.atZoneSameInstant(ZoneId.systemDefault).toLocalDateTime)
      case timeStamp: java.sql.Timestamp => Some(timeStamp.toLocalDateTime)
      case other => throw IllegalArgumentException(s"Invalid type for date/time field: $other")

  override protected def getBooleanField(value: AnyRef): Boolean = value.asInstanceOf[Boolean]

  override protected def getNextVal(tableName: String): AnyRef =
    val labelSql = f"SELECT nextval('$tableName')"
    val labelRes = executeAndGetOne(labelSql, List())
    labelRes("nextval")

  override val forUpdate: String = "FOR UPDATE"
