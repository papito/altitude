package altitude.core.unit

import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.routes.SearchRequestParser.Scope
import altitude.core.util.BoundingBox
import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchCursor
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

  test("A date range needs an end, and its first day is not after its last") {

    /**
     * Setup:
     *
     * Date ranges with both ends, with one end, with one day as both ends, with no end, and with the first day after the last.
     *
     * Assertions:
     *
     * A range with both ends or with either one alone is accepted; a range with no end and a range whose first day is after its
     * last are refused when they are constructed.
     *
     * Edge cases:
     *
     * One day as both ends is a range of that single day.
     */
    val first = LocalDate.parse("2024-06-01")
    val last = LocalDate.parse("2024-06-30")

    DateRange(Some(first), Some(last)).to shouldBe Some(last)
    DateRange(Some(first), None).to shouldBe None
    DateRange(None, Some(last)).from shouldBe None
    DateRange(Some(first), Some(first)).from shouldBe Some(first)

    intercept[IllegalArgumentException](DateRange(None, None))
    intercept[IllegalArgumentException](DateRange(Some(last), Some(first)))
  }

  test("A query matches all of its people unless told any, and every copy keeps its people match and date ranges") {

    /**
     * Setup:
     *
     * A query with no people match given, and a query matching any of its people within a Date Taken and a Date Imported range,
     * copied each way a query is copied: with a folder set, a metadata filter, a column filter, a text probe limit and no total.
     *
     * Assertions:
     *
     * The query with no people match given matches all, and has no date range. Every copy of the other still matches any of its
     * people within both ranges.
     */
    val taken = DateRange(Some(LocalDate.parse("2024-06-01")), Some(LocalDate.parse("2024-06-30")))
    val imported = DateRange(None, Some(LocalDate.parse("2024-07-04")))

    val plain = new SearchQuery()
    plain.peopleMatch shouldBe PeopleMatch.All
    plain.takenRange shouldBe None
    plain.importedRange shouldBe None

    val query = new SearchQuery(peopleMatch = PeopleMatch.Any, takenRange = Some(taken), importedRange = Some(imported))
    val copies = List(
      query.withFolderIds(Set("f1")),
      query.add_metadata_filter("field-1" -> "beach"),
      query.add("is_recycled" -> false),
      query.withTextProbeLimit(1),
      query.withoutTotal
    )

    for (copy <- copies) withClue(copy) {
      copy.peopleMatch shouldBe PeopleMatch.Any
      copy.takenRange shouldBe Some(taken)
      copy.importedRange shouldBe Some(imported)
    }
  }

  test("A cursor's fingerprint tells apart the people match and each date range") {

    /**
     * Setup:
     *
     * A search of two people, and the same search matching any of them, within a Date Taken range, within another Date Taken
     * range, within a Date Imported range of the first one's days, and within both ranges.
     *
     * Assertions:
     *
     * The six searches have six fingerprints, so a cursor of one cannot continue another, and the same search has the same
     * fingerprint again.
     *
     * Edge cases:
     *
     * The same days as a Date Taken range and as a Date Imported range are two searches.
     */
    val june = DateRange(Some(LocalDate.parse("2024-06-01")), Some(LocalDate.parse("2024-06-30")))
    val fromJune = DateRange(Some(LocalDate.parse("2024-06-01")), None)
    def fingerprint(
        peopleMatch: PeopleMatch = PeopleMatch.All,
        takenRange: Option[DateRange] = None,
        importedRange: Option[DateRange] = None): String =
      SearchCursor.scopeFingerprint(
        new SearchQuery(
          personIds = Set("p1", "p2"),
          peopleMatch = peopleMatch,
          takenRange = takenRange,
          importedRange = importedRange),
        repositoryId = "repo-1",
        dbEngine = "sqlite"
      )

    val fingerprints = List(
      fingerprint(),
      fingerprint(peopleMatch = PeopleMatch.Any),
      fingerprint(takenRange = Some(june)),
      fingerprint(takenRange = Some(fromJune)),
      fingerprint(importedRange = Some(june)),
      fingerprint(takenRange = Some(june), importedRange = Some(june))
    )

    fingerprints.distinct.size shouldBe fingerprints.size
    fingerprint(takenRange = Some(june)) shouldBe fingerprints(2)
  }

  test("A scope hands its people match and date ranges to the query it builds") {

    /**
     * Setup:
     *
     * A parsed scope of two people matching any of them within a Date Taken and a Date Imported range, and a scope with none of
     * the three given.
     *
     * Assertions:
     *
     * The query built from the first scope carries its people match and both ranges; the query built from the second matches all
     * of its people and has no date range.
     */
    val taken = DateRange(Some(LocalDate.parse("2024-06-01")), None)
    val imported = DateRange(None, Some(LocalDate.parse("2024-07-04")))
    val plain = Scope(Map(), None, Set(), Set("p1", "p2"), Set(), Set(), None, None)

    val query = plain.copy(peopleMatch = PeopleMatch.Any, takenRange = Some(taken), importedRange = Some(imported)).query()
    query.peopleMatch shouldBe PeopleMatch.Any
    query.takenRange shouldBe Some(taken)
    query.importedRange shouldBe Some(imported)

    val unfiltered = plain.query()
    unfiltered.peopleMatch shouldBe PeopleMatch.All
    unfiltered.takenRange shouldBe None
    unfiltered.importedRange shouldBe None
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
