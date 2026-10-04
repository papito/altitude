package altitude.core.unit

import altitude.test.IntegrationTestUtil.withJvmTimeZone
import altitude.test.TestFocus
import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
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

    /**
     * Setup:
     *
     * The asset row class, turned into table operations through each of this project's dialects.
     *
     * Assertions:
     *
     * Both dialects build the base `TableOps`, which threads the dialect it is given. ScalaSql's `SqliteDialect.TableOps`
     * resolves the dialect from the library's `SqliteDialect` object instead of the one in scope. A table built through it reads
     * and writes every column with the stock type mappers, which silently loses this project's timestamp handling; the symptom is
     * a wall-clock timestamp shifted by the JVM zone, far from its cause.
     */
    AltitudeSqliteDialect.TableOpsConv(AssetRow).getClass shouldBe classOf[TableOps[?]]
    AltitudePostgresDialect.TableOpsConv(AssetRow).getClass shouldBe classOf[TableOps[?]]
  }

  test("SQLite timestamps are the text the schema stores, with no zone conversion") {

    /**
     * Setup:
     *
     * A wall-clock time that falls in the US daylight-saving gap (2026-03-08 02:30), the SQLite dialect's temporal type mappers
     * run against stubbed JDBC statements and rows, and the JVM zone set to America/New_York, where that time does not exist.
     *
     * Assertions:
     *
     * Every temporal type binds as text. The wall-clock mapper writes and reads exactly the `yyyy-MM-dd HH:mm:ss` text the schema
     * stores, and the instant mapper writes an instant's UTC wall clock and reads the text back as that moment at UTC.
     *
     * Edge cases:
     *
     * A time inside a daylight-saving gap, which any round trip through the JVM zone would move by an hour.
     */
    val wallClock = LocalDateTime.of(2026, 3, 8, 2, 30, 0)
    // The stored format, which the day expression `date(col)` and a cursor comparison both depend on
    val stored = "2026-03-08 02:30:00"

    AltitudeSqliteDialect.LocalDateTimeType.jdbcType shouldBe JDBCType.VARCHAR
    AltitudeSqliteDialect.OffsetDateTimeType.jdbcType shouldBe JDBCType.VARCHAR
    AltitudeSqliteDialect.LocalDateType.jdbcType shouldBe JDBCType.VARCHAR

    withJvmTimeZone("America/New_York") {
      val statement = mock(classOf[PreparedStatement])
      AltitudeSqliteDialect.LocalDateTimeType.put(statement, 1, wallClock)
      AltitudeSqliteDialect.OffsetDateTimeType.put(statement, 2, wallClock.atOffset(ZoneOffset.ofHours(-5)))
      verify(statement).setString(1, stored)
      verify(statement).setString(2, "2026-03-08 07:30:00")

      val row = mock(classOf[ResultSet])
      when(row.getString(1)).thenReturn(stored)
      AltitudeSqliteDialect.LocalDateTimeType.get(row, 1) shouldBe wallClock
      AltitudeSqliteDialect.OffsetDateTimeType.get(row, 1) shouldBe wallClock.atOffset(ZoneOffset.UTC)
    }
  }

  test("PostgreSQL reads an instant as the moment the column stores") {

    /**
     * Setup:
     *
     * The PostgreSQL dialect's temporal type mappers.
     *
     * Assertions:
     *
     * An instant maps to `TIMESTAMP WITH TIME ZONE`, and the wall-clock and calendar-day types keep the stock mappings.
     */
    AltitudePostgresDialect.OffsetDateTimeType.jdbcType shouldBe JDBCType.TIMESTAMP_WITH_TIMEZONE

    // The wall-clock and calendar-day mappers are the stock ones, which already read the java.time types
    AltitudePostgresDialect.LocalDateTimeType.jdbcType shouldBe JDBCType.TIMESTAMP
    AltitudePostgresDialect.LocalDateType.jdbcType shouldBe JDBCType.DATE
  }

  test("Bound parameters are never wrapped in a CAST") {

    /**
     * Setup:
     *
     * Both of this project's dialects.
     *
     * Assertions:
     *
     * Neither dialect casts its bound parameters, so the SQL carries a plain placeholder for each. Identifier quoting is covered
     * by RowColumnTests.
     */
    AltitudeSqliteDialect.castParams shouldBe false
    AltitudePostgresDialect.castParams shouldBe false
  }
}
