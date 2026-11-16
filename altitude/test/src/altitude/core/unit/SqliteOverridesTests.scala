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
     * A whole second, the same second with a quarter and with a millisecond.
     *
     * Assertions:
     *
     * A whole second is written as `yyyy-MM-dd HH:mm:ss` and a fraction is appended with no trailing zeros; every form parses
     * back to its value. Only canonical forms are parsed, since every writer produces them for the stored text to compare as
     * text.
     */
    val second = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    val formatter = SqliteOverrides.DATETIME_FORMATTER

    second.format(formatter) shouldBe "2024-07-04 08:09:10"
    second.withNano(250_000_000).format(formatter) shouldBe "2024-07-04 08:09:10.25"
    second.withNano(1_000_000).format(formatter) shouldBe "2024-07-04 08:09:10.001"

    List(
      "2024-07-04 08:09:10" -> second,
      "2024-07-04 08:09:10.25" -> second.withNano(250_000_000),
      "2024-07-04 08:09:10.001" -> second.withNano(1_000_000)
    ).foreach {
      case (text, expected) =>
        withClue(text) {
          LocalDateTime.parse(text, formatter) shouldBe expected
          LocalDateTime.parse(expected.format(formatter), formatter) shouldBe expected
        }
    }
  }
}
