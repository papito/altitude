package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.DataScrubber

@DoNotDiscover class DataScrubberTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test data scrubbing in bulk") {

    /**
     * Setup:
     *
     * A scrubber that trims two fields and lowercases one of them, over a JSON object with padded, mixed-case values.
     *
     * Assertions:
     *
     * Each field is scrubbed by exactly the rules listed for it: one is only trimmed, the other trimmed and lowercased.
     */
    val dataScrubber = DataScrubber(
      trim = List("toTrim", "toTrimAndLower"),
      lower = List("toTrimAndLower")
    )

    val jsonIn = ujson.Obj(
      "toTrim" -> "  what a mess   ",
      "toTrimAndLower" -> "  tyPe beTTEr "
    )

    val scrubbedJson = dataScrubber.scrub(jsonIn)
    scrubbedJson("toTrim").str shouldBe "what a mess"
    scrubbedJson("toTrimAndLower").str shouldBe "type better"
  }
}
