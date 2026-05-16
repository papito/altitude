package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.DataScrubber

@DoNotDiscover class DataScrubberTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test data scrubbing in bulk") {
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
