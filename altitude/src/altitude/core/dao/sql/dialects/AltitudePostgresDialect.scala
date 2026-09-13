package altitude.core.dao.sql.dialects

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import scalasql.core.TypeMapper
import scalasql.dialects.PostgresDialect

/**
 * PostgreSQL for this schema.
 *
 * The stock mappers already read `timestamp` as a `LocalDateTime` and `date` as a `LocalDate`, which is what
 * `PostgresOverrides.javaTimeRowProcessor` does for the raw paths. Only `timestamptz` is pinned here, so an instant is read as
 * the moment the column stores rather than through `java.sql.Timestamp` in the JVM zone.
 */
object AltitudePostgresDialect extends PostgresDialect:

  implicit override def OffsetDateTimeType: TypeMapper[OffsetDateTime] = offsetDateTimeType

  private val offsetDateTimeType: TypeMapper[OffsetDateTime] = new TypeMapper[OffsetDateTime]:
    def jdbcType: JDBCType = JDBCType.TIMESTAMP_WITH_TIMEZONE
    override def castTypeString: String = "TIMESTAMP WITH TIME ZONE"

    def get(r: ResultSet, idx: Int): OffsetDateTime = r.getObject(idx, classOf[OffsetDateTime])

    def put(r: PreparedStatement, idx: Int, v: OffsetDateTime): Unit = r.setObject(idx, v)
