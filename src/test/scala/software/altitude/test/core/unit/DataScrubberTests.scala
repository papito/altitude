package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import play.api.libs.json.Json
import software.altitude.core.DataScrubber
import software.altitude.test.core.TestFocus


@DoNotDiscover class DataScrubberTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test data scrubbing in bulk") {
    val dataScrubber = DataScrubber(
      trim = List("toTrim", "toTrimAndLower"),
      lower = List("toTrimAndLower"),
    )

    val jsonIn = Json.obj(
      "toTrim" -> "  what a mess   ",
      "toTrimAndLower" -> "  tyPe beTTEr ",
    )

    val scrubbedJson = dataScrubber.scrub(jsonIn)
    (scrubbedJson \ "toTrim").as[String] should be("what a mess")
    (scrubbedJson \ "toTrimAndLower").as[String] should be("type better")
  }
}
