package altitude.core.unit

import altitude.test.TestFocus
import java.sql.JDBCType
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe
import scalasql.dialects.TableOps

import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables.AssetRow

/** The engine dialects, and the one place ScalaSql would otherwise route around them */
@DoNotDiscover class SqlDialectTests extends funsuite.AnyFunSuite with TestFocus {

  test("Table operations carry this project's dialect, not the library's own") {
    /*
     * ScalaSql's `SqliteDialect.TableOps` resolves the dialect from the library's `SqliteDialect` object instead of the one in
     * scope. A table built through it reads and writes every column with the stock type mappers, which silently loses the
     * timestamp handling below; the symptom is a wall-clock timestamp shifted by the JVM zone, far from its cause.
     */
    AltitudeSqliteDialect.TableOpsConv(AssetRow).getClass shouldBe classOf[TableOps[?]]
    AltitudePostgresDialect.TableOpsConv(AssetRow).getClass shouldBe classOf[TableOps[?]]
  }

  test("SQLite timestamps are the text the schema stores, with no zone conversion") {
    val wallClock = LocalDateTime.of(2026, 3, 8, 2, 30, 0)

    AltitudeSqliteDialect.LocalDateTimeType.jdbcType shouldBe JDBCType.VARCHAR
    AltitudeSqliteDialect.OffsetDateTimeType.jdbcType shouldBe JDBCType.VARCHAR
    AltitudeSqliteDialect.LocalDateType.jdbcType shouldBe JDBCType.VARCHAR

    // The stored format, which the day expression `date(col)` and a cursor comparison both depend on
    wallClock.format(altitude.core.dao.sqlite.SqliteOverrides.DATETIME_FORMATTER) shouldBe "2026-03-08 02:30:00"
  }

  test("PostgreSQL reads an instant as the moment the column stores") {
    AltitudePostgresDialect.OffsetDateTimeType.jdbcType shouldBe JDBCType.TIMESTAMP_WITH_TIMEZONE

    // The wall-clock and calendar-day mappers are the stock ones, which already read the java.time types
    AltitudePostgresDialect.LocalDateTimeType.jdbcType shouldBe JDBCType.TIMESTAMP
    AltitudePostgresDialect.LocalDateType.jdbcType shouldBe JDBCType.DATE
  }

  test("Identifiers are never quoted, so an expression index can still match a column") {
    AltitudeSqliteDialect.castParams shouldBe false
    AltitudePostgresDialect.castParams shouldBe false
  }
}
