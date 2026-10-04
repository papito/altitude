package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.util.BoundingBox
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort

@DoNotDiscover class SearchQueryModelTests extends funsuite.AnyFunSuite {

  test("Invalid RPP") {

    /**
     * Setup:
     *
     * A search query asked for -1 results per page.
     *
     * Assertions:
     *
     * The query is refused when it is constructed.
     */
    intercept[IllegalArgumentException] {
      new SearchQuery(rpp = -1)
    }
  }

  test("The Relevance sort needs Search text with a usable term") {

    /**
     * Setup:
     *
     * Queries sorted by Relevance: one with the text "beach", one without text, and one whose text "- OR" has no usable term.
     *
     * Assertions:
     *
     * Only the query with a usable term is accepted, as a text search; the other two are refused.
     *
     * Edge cases:
     *
     * Text that is present but parses to nothing counts as no text.
     */
    new SearchQuery(text = Some("beach"), searchSort = List(SearchSort.Relevance)).isText shouldBe true
    intercept[IllegalArgumentException](new SearchQuery(searchSort = List(SearchSort.Relevance)))
    intercept[IllegalArgumentException](new SearchQuery(text = Some("- OR"), searchSort = List(SearchSort.Relevance)))
  }

  test("A bounding box parses south,west,north,east, checks its ranges and knows the antimeridian") {

    /**
     * Setup:
     *
     * Bounding box strings: a Paris box with spaces around its values, a box across the date line, and a list of malformed or
     * out-of-range ones.
     *
     * Assertions:
     *
     * A valid box parses into its south, west, north and east edges, round-trips through its string form and knows whether it
     * crosses the antimeridian; every malformed or out-of-range string is refused.
     *
     * Edge cases:
     *
     * West greater than east across the date line, an empty string, too few or too many parts, a value that is not a number,
     * latitudes and longitudes out of range, a south edge north of the north edge, and NaN.
     */
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
