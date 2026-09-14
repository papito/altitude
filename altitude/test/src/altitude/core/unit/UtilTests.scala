package altitude.core.unit

import altitude.test.TestFocus
import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldEqual

import altitude.core.util.Util

@DoNotDiscover class UtilTests extends funsuite.AnyFunSuite with TestFocus {

  test("A calendar day reads as weekday, month, day and year, with no zero padding") {
    Util.humanReadableDate(LocalDate.parse("2026-09-06")) shouldEqual "Sunday, September 6, 2026"
    Util.humanReadableDate(LocalDate.parse("2025-01-02")) shouldEqual "Thursday, January 2, 2025"
    Util.humanReadableDate(LocalDate.parse("2024-12-25")) shouldEqual "Wednesday, December 25, 2024"
  }

  test("A duration reads as minutes and seconds, with hours only from an hour on") {
    Util.humanReadableDuration(0) shouldEqual "0:00"
    Util.humanReadableDuration(2_499) shouldEqual "0:02"
    Util.humanReadableDuration(2_500) shouldEqual "0:03"
    Util.humanReadableDuration(65_000) shouldEqual "1:05"
    Util.humanReadableDuration(59 * 60_000 + 59_000) shouldEqual "59:59"
    Util.humanReadableDuration(3_600_000) shouldEqual "1:00:00"
    Util.humanReadableDuration(2 * 3_600_000 + 5 * 60_000 + 7_000) shouldEqual "2:05:07"
  }

  test("A date group's match count reads as a parenthesized item count, singular for one") {
    Util.humanReadableItemCount(0) shouldEqual "(0 items)"
    Util.humanReadableItemCount(1) shouldEqual "(1 item)"
    Util.humanReadableItemCount(2) shouldEqual "(2 items)"
    Util.humanReadableItemCount(342) shouldEqual "(342 items)"
  }
}
