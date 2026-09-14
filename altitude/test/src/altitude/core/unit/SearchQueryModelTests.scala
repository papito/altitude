package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.util.BoundingBox
import altitude.core.util.SearchQuery

@DoNotDiscover class SearchQueryModelTests extends funsuite.AnyFunSuite {

  test("Invalid RPP") {
    intercept[IllegalArgumentException] {
      new SearchQuery(rpp = -1)
    }
  }

  test("Invalid page") {
    intercept[IllegalArgumentException] {
      new SearchQuery(page = 0)
    }
    intercept[IllegalArgumentException] {
      new SearchQuery(page = -1)
    }
  }

  test("A bounding box parses south,west,north,east, checks its ranges and knows the antimeridian") {
    val box = BoundingBox.parse(" 48.8, 2.2, 48.9 ,2.4")
    box shouldBe BoundingBox(48.8, 2.2, 48.9, 2.4)
    box.crossesAntimeridian shouldBe false
    BoundingBox.parse(box.toString) shouldBe box

    // West of the east edge across the date line
    BoundingBox.parse("-1,179,1,-179").crossesAntimeridian shouldBe true

    List("", "1,2,3", "1,2,3,4,5", "a,2,3,4", "91,0,92,1", "0,181,1,182", "0,0,-1,1", "NaN,0,1,1").foreach {
      text =>
        withClue(text) {
          intercept[IllegalArgumentException](BoundingBox.parse(text))
        }
    }
  }
}
