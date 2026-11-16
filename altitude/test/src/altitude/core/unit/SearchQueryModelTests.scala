package altitude.core.unit

import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.FieldConst
import altitude.core.routes.SearchRequestParser.Scope
import altitude.core.util.BoundingBox
import altitude.core.util.DateRange
import altitude.core.util.DescribedPart
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchCursor
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

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
     * Only the query with a usable term is accepted, as a text search.
     *
     * Edge cases:
     *
     * Text that parses to nothing counts as no text.
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
     * A range with both ends or either one is accepted; one with no end or with its first day after its last is refused.
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

  test("A date range reads as its days, each month by its three letters") {

    /**
     * Setup:
     *
     * Ranges within one year and across two, a single day, ranges open at either end, and the 8th of every month of 2024.
     *
     * Assertions:
     *
     * A range within a year names the year once, at the end; across two years each end has its year; a single day reads once; an
     * open range reads `from` or `until` its day. Every month is its English three letters, a fixed table like `MONTHS` in the
     * date field's `date-range.js`.
     *
     * Edge cases:
     *
     * September is `Sep`, where British English abbreviates it `Sept`.
     */
    def day(iso: String) = Some(LocalDate.parse(iso))

    DateRange(day("2024-06-01"), day("2024-06-30")).label shouldBe "1 Jun – 30 Jun 2024"
    DateRange(day("2023-12-28"), day("2024-01-03")).label shouldBe "28 Dec 2023 – 3 Jan 2024"
    DateRange(day("2024-06-08"), day("2024-06-08")).label shouldBe "8 Jun 2024"
    DateRange(day("2024-06-08"), None).label shouldBe "from 8 Jun 2024"
    DateRange(None, day("2024-06-21")).label shouldBe "until 21 Jun 2024"
    DateRange(day("2024-09-08"), day("2024-09-21")).label shouldBe "8 Sep – 21 Sep 2024"

    val months = List("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    for ((month, index) <- months.zipWithIndex) {
      val eighth = Some(LocalDate.of(2024, index + 1, 8))
      DateRange(eighth, eighth).label shouldBe s"8 $month 2024"
    }
  }

  test("A query matches all of its people unless told any, and every copy keeps its people match and date ranges") {

    /**
     * Setup:
     *
     * A plain query, and a query matching any of its people within a Date Taken and a Date Imported range, copied each way a
     * query is copied.
     *
     * Assertions:
     *
     * The plain query matches all its people and has no date range. Every copy of the other keeps its people match and ranges.
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
     * A search of two people, then the same matching any of them, within either of two Date Taken ranges, within a Date Imported
     * range of the same days, and within both ranges.
     *
     * Assertions:
     *
     * The six searches have six fingerprints, so a cursor of one cannot continue another, and a search's fingerprint is stable.
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

  test("A Describe search's fingerprint is its description with its filters' search, and no other search's") {

    /**
     * Setup:
     *
     * Fingerprints of a Describe search of the repository view, varied by description, repository, engine and each filter in
     * turn; of the same filters with no description; and of the search sorted by file name, in each of its two parts and in the
     * other direction.
     *
     * Assertions:
     *
     * Every fingerprint differs, so a cursor of one cannot continue another, and a Describe search's fingerprint is stable.
     *
     * Edge cases:
     *
     * A description is fingerprinted as written, so another case or an extra space is another search, and so is Search text of
     * the same words. Each part of a sorted Describe search has its own fingerprint.
     */
    val june = DateRange(Some(LocalDate.parse("2024-06-01")), Some(LocalDate.parse("2024-06-30")))
    def view(triaged: Boolean): Map[String, Any] =
      Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false) ++ Option.unless(triaged)(FieldConst.Asset.IS_TRIAGED -> false)
    val unfiltered = new SearchQuery(params = view(triaged = false))
    val filtered = List(
      new SearchQuery(params = view(triaged = true)),
      new SearchQuery(params = view(triaged = false), text = Some("beach")),
      new SearchQuery(params = view(triaged = false), folderIds = Set("trips")),
      new SearchQuery(params = view(triaged = false), personIds = Set("alice")),
      new SearchQuery(params = view(triaged = false), personIds = Set("alice", "bob")),
      new SearchQuery(params = view(triaged = false), personIds = Set("alice", "bob"), peopleMatch = PeopleMatch.Any),
      new SearchQuery(params = view(triaged = false), locationIds = Set("lisbon")),
      new SearchQuery(params = view(triaged = false), takenRange = Some(june)),
      new SearchQuery(params = view(triaged = false), importedRange = Some(june))
    )

    def fingerprint(
        description: String = "a cactus",
        query: SearchQuery = unfiltered,
        repositoryId: String = "repo-1",
        dbEngine: String = "sqlite",
        part: Option[DescribedPart] = None): String =
      SearchCursor.describeFingerprint(description, query, repositoryId, dbEngine, part)
    def byName(direction: SortDirection): SearchQuery =
      new SearchQuery(params = view(triaged = false), searchSort = List(SearchSort(FieldConst.Asset.FILENAME, direction)))
    def withoutDescription(query: SearchQuery): String =
      SearchCursor.scopeFingerprint(query, repositoryId = "repo-1", dbEngine = "sqlite")

    val fingerprints = List(
      fingerprint(),
      fingerprint(description = "a man in a suit"),
      fingerprint(repositoryId = "repo-2"),
      fingerprint(dbEngine = "postgres"),
      fingerprint(description = "A cactus"),
      fingerprint(description = "a  cactus"),
      withoutDescription(new SearchQuery(params = view(triaged = false), text = Some("a cactus"))),
      fingerprint(query = byName(SortDirection.ASC), part = Some(DescribedPart.Nearer)),
      fingerprint(query = byName(SortDirection.ASC), part = Some(DescribedPart.Weaker)),
      fingerprint(query = byName(SortDirection.DESC), part = Some(DescribedPart.Nearer))
    ) ++ filtered.map(query => fingerprint(query = query)) ++ (unfiltered :: filtered).map(withoutDescription)

    fingerprints.distinct.size shouldBe fingerprints.size
    fingerprint() shouldBe fingerprints.head
    fingerprint(query = new SearchQuery(params = view(triaged = false), takenRange = Some(june))) shouldBe
      fingerprint(query = filtered(7))
  }

  test("A copy of a search keeps how many assets a Describe search ranks exactly") {

    /**
     * Setup:
     *
     * A search with the default exact Describe limit, and one with a limit of 7 copied with a folder scope and without its total.
     *
     * Assertions:
     *
     * The first's limit is the 75,000 default, and the copy keeps 7.
     */
    val view = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false, FieldConst.Asset.IS_TRIAGED -> false)

    new SearchQuery(params = view).describeExactLimit shouldBe 75000
    val tuned = new SearchQuery(params = view, describeExactLimit = 7).withFolderIds(Set("trips")).withoutTotal
    tuned.describeExactLimit shouldBe 7
  }

  test("A cursor carries a Describe search's position, a distance, through its token unchanged") {

    /**
     * Setup:
     *
     * Cursors whose positions are cosine distances no short decimal spells: a third, the smallest positive double, a distance
     * past 1, and a widened float.
     *
     * Assertions:
     *
     * Each decodes from its token bit for bit, so a page continues exactly after the row the cursor names.
     */
    List(1.0 / 3, java.lang.Double.MIN_VALUE, 1.0000001192092896, 0.9400720596313477f.toDouble).foreach {
      distance =>
        val cursor = SearchCursor(None, None, SortValue.Real(distance), "asset-1", scope = "fingerprint")
        SearchCursor.decode(cursor.encode) shouldBe cursor
    }
  }

  test("A scope hands its people match and date ranges to the query it builds") {

    /**
     * Setup:
     *
     * A scope of two people matching any of them within a Date Taken and a Date Imported range, and one with none of the three.
     *
     * Assertions:
     *
     * The first scope's query carries its people match and both ranges; the second's matches all its people with no range.
     */
    val taken = DateRange(Some(LocalDate.parse("2024-06-01")), None)
    val imported = DateRange(None, Some(LocalDate.parse("2024-07-04")))
    val plain = Scope(Map(), None, Set(), List("p1", "p2"), Set(), Nil, None, None)

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
     * A Paris box with spaces around its values, a box across the date line, and malformed or out-of-range strings.
     *
     * Assertions:
     *
     * A valid box parses into its edges, round-trips through its string form and knows whether it crosses the antimeridian; every
     * malformed or out-of-range string is refused.
     *
     * Edge cases:
     *
     * A west edge greater than the east edge crosses the date line; a south edge north of the north edge is refused.
     */
    val box = BoundingBox.parse(" 48.8, 2.2, 48.9 ,2.4")
    box shouldBe BoundingBox(48.8, 2.2, 48.9, 2.4)
    box.crossesAntimeridian shouldBe false
    BoundingBox.parse(box.toString) shouldBe box

    BoundingBox.parse("-1,179,1,-179").crossesAntimeridian shouldBe true

    List("", "1,2,3", "1,2,3,4,5", "a,2,3,4", "91,0,92,1", "0,181,1,182", "0,0,-1,1", "NaN,0,1,1").foreach {
      text =>
        withClue(text) {
          intercept[IllegalArgumentException](BoundingBox.parse(text))
        }
    }
  }
}
