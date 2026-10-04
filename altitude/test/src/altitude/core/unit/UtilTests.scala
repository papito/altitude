package altitude.core.unit

import altitude.test.TestFocus
import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldEqual

import altitude.core.util.Util

@DoNotDiscover class UtilTests extends funsuite.AnyFunSuite with TestFocus {

  test("A calendar day reads as weekday, month, day and year, with no zero padding") {

    /**
     * Setup:
     *
     * Three calendar days, two of them with single-digit days.
     *
     * Assertions:
     *
     * Each day reads as the full English weekday and month, the day without zero padding and the four-digit year.
     */
    Util.humanReadableDate(LocalDate.parse("2026-09-06")) shouldEqual "Sunday, September 6, 2026"
    Util.humanReadableDate(LocalDate.parse("2025-01-02")) shouldEqual "Thursday, January 2, 2025"
    Util.humanReadableDate(LocalDate.parse("2024-12-25")) shouldEqual "Wednesday, December 25, 2024"
  }

  test("A duration reads as minutes and seconds, with hours only from an hour on") {

    /**
     * Setup:
     *
     * Durations in milliseconds from zero up to just over two hours.
     *
     * Assertions:
     *
     * A duration under an hour reads as m:ss and one from an hour on as h:mm:ss, rounded to the nearest second.
     *
     * Edge cases:
     *
     * Zero, the half-second rounding boundary (2,499 vs 2,500 ms), and the last second before an hour against the hour itself.
     */
    Util.humanReadableDuration(0) shouldEqual "0:00"
    Util.humanReadableDuration(2_499) shouldEqual "0:02"
    Util.humanReadableDuration(2_500) shouldEqual "0:03"
    Util.humanReadableDuration(65_000) shouldEqual "1:05"
    Util.humanReadableDuration(59 * 60_000 + 59_000) shouldEqual "59:59"
    Util.humanReadableDuration(3_600_000) shouldEqual "1:00:00"
    Util.humanReadableDuration(2 * 3_600_000 + 5 * 60_000 + 7_000) shouldEqual "2:05:07"
  }

  test("A date group's match count reads as a parenthesized item count, singular for one") {

    /**
     * Setup:
     *
     * Item counts of 0, 1, 2 and 342.
     *
     * Assertions:
     *
     * Each count reads in parentheses, with "item" singular for exactly one and plural otherwise, zero included.
     */
    Util.humanReadableItemCount(0) shouldEqual "(0 items)"
    Util.humanReadableItemCount(1) shouldEqual "(1 item)"
    Util.humanReadableItemCount(2) shouldEqual "(2 items)"
    Util.humanReadableItemCount(342) shouldEqual "(342 items)"
  }
}
