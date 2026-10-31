package altitude.core.unit

import altitude.test.TestFocus
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.dao.sqlite.SqliteOverrides

/** The one DATETIME text format SQLite rows are written in and read from */
@DoNotDiscover class SqliteOverridesTests extends AnyFunSuite with TestFocus {

  test("The DATETIME formatter writes a fraction only when there is one, and reads both forms back") {

    /**
     * Setup:
     *
     * A whole second, the same second with a quarter and with a millisecond, and the stored forms of a legacy row written from a
     * `java.sql.Timestamp` (a zero fraction, nine digits).
     *
     * Assertions:
     *
     * A whole second is written exactly as before, `yyyy-MM-dd HH:mm:ss`, so existing rows and the `date()` index expressions see
     * one format; a fraction is appended with no trailing zeros, and every form parses back to the value it spells.
     */
    val second = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    val formatter = SqliteOverrides.DATETIME_FORMATTER

    second.format(formatter) shouldBe "2024-07-04 08:09:10"
    second.withNano(250_000_000).format(formatter) shouldBe "2024-07-04 08:09:10.25"
    second.withNano(1_000_000).format(formatter) shouldBe "2024-07-04 08:09:10.001"

    List(
      "2024-07-04 08:09:10" -> second,
      "2024-07-04 08:09:10.25" -> second.withNano(250_000_000),
      "2024-07-04 08:09:10.001" -> second.withNano(1_000_000),
      "2024-07-04 08:09:10.0" -> second,
      "2024-07-04 08:09:10.123456789" -> second.withNano(123_456_789)
    ).foreach {
      case (text, expected) =>
        withClue(text) {
          LocalDateTime.parse(text, formatter) shouldBe expected
          LocalDateTime.parse(expected.format(formatter), formatter) shouldBe expected
        }
    }
  }
}
