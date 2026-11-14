package altitude.core.dao.postgres

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.apache.commons.dbutils.BasicRowProcessor
import org.apache.commons.dbutils.RowProcessor
import org.postgresql.util.PGobject
import scalasql.dialects.Dialect

import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.models.SceneEmbedding

object PostgresOverrides:

  /**
   * Floats (a Face's `Array`, a Scene embedding's `IArray`) as a pgvector literal, e.g. "[0.1,0.2,...]", which pgvector accepts
   * when cast with `?::vector` or `?::halfvec`; also what a `vector` column reads back as, through `getVectorField`
   */
  def vectorLiteral(values: Iterable[Float]): String = values.mkString("[", ",", "]")

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

  override def instantOf(value: LocalDateTime): Instant = value.atZone(ZoneId.systemDefault).toInstant

  override protected def jsonFunc = "CAST(? as jsonb)"

  override protected def jsonText(column: String, key: String): String = s"($column->>'$key')"

  override protected def nativeBool(value: Boolean): Any =
    if value then true else false

  override protected val vectorPlaceholder: String = "?::vector"

  // The literal is bound as text and cast by the placeholder, as `FaceDao.add` binds a Face's features
  override protected def vectorBind(embedding: Option[SceneEmbedding]): Any =
    embedding.map(e => PostgresOverrides.vectorLiteral(e.values)).orNull

  // pgJDBC hands a `vector` column out as a PGobject whose value is the literal `vectorLiteral` writes
  override protected def getVectorField(value: AnyRef): Option[SceneEmbedding] = Option(value).map {
    case vector: PGobject =>
      SceneEmbedding(IArray.unsafeFromArray(vector.getValue.stripPrefix("[").stripSuffix("]").split(',').map(_.trim.toFloat)))
    case other => throw IllegalArgumentException(s"Invalid type for vector field: $other")
  }

  override protected def nativeLocalDateTime(value: LocalDateTime): Any = value

  override protected def nativeUtcTimestamp(value: OffsetDateTime): Any = value

  override protected def nativeLocalDate(value: LocalDate): Any = value

  override protected def dayOf(column: String): String = PostgresSearchDialect.dayOf(column)

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

  override val forKeyShare: String = "FOR KEY SHARE"

  override val forNoKeyUpdate: String = "FOR NO KEY UPDATE"

  override protected def advisoryLock(key: String): Unit =
    manyBySqlQuery("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", List(key)): Unit

  override protected val inIdSet: String = "= ANY(?)"

  // varchar[] rather than text[]: against a CHAR(36) column it is the array that is cast, and the column's index is used
  override protected def idSet(ids: Set[String]): Any =
    RequestContext.getConn.createArrayOf("varchar", ids.toArray[AnyRef])
