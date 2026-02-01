package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import play.api.libs.json.Json
import altitude.core.DataScrubber
import altitude.test.TestFocus
import org.scalatest.matchers.should.Matchers.{convertToStringShouldWrapperForVerb, shouldBe}


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
    (scrubbedJson \ "toTrim").as[String] shouldBe "what a mess"
    (scrubbedJson \ "toTrimAndLower").as[String] shouldBe "type better"
  }
}
