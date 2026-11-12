package altitude.core.unit

import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.EitherValues.*
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.SearchRequestParser.Scope
import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch

/** The parameters of an advanced search as `SearchRequestParser.parse` reads them into a scope */
@DoNotDiscover class SearchRequestParserTests extends funsuite.AnyFunSuite {

  private val fields = Api.Field.Search

  /** A request of `view` with the given parameters, by their names in the query string */
  private def parse(view: String, params: (String, String)*): Either[String, Scope] = {
    val values = params.toMap
    SearchRequestParser.parse(
      view = view,
      q = values.get(fields.QUERY_TEXT),
      folderId = values.get(fields.FOLDER_ID),
      personId = values.get(fields.PERSON_ID),
      albumId = values.get(fields.ALBUM_ID),
      locationId = values.get(fields.LOCATION_ID),
      bbox = values.get(fields.BBOX),
      triaged = values.get(fields.TRIAGED).map(_.toBoolean),
      subset = values.get(fields.SUBSET),
      peopleMatch = values.get(fields.PEOPLE_MATCH),
      takenFrom = values.get(fields.TAKEN_FROM),
      takenTo = values.get(fields.TAKEN_TO),
      importedFrom = values.get(fields.IMPORTED_FROM),
      importedTo = values.get(fields.IMPORTED_TO)
    )
  }

  private def browse(params: (String, String)*): Either[String, Scope] = parse(Const.Search.View.DEFAULT, params*)

  private def ids(count: Int): String = (1 to count).map(n => s"id-$n").mkString(",")

  test("A person or Location parameter names one ID or several, separated by commas") {

    /**
     * Setup:
     *
     * Requests of the repository view naming no person or Location, one of each, and three people with two Locations.
     *
     * Assertions:
     *
     * A parameter left out is no filter, one ID is a set of one, and a comma-separated value is the set of its IDs; the folder
     * and the album stay one ID each.
     *
     * Edge cases:
     *
     * An ID repeated in a parameter counts once.
     */
    val none = browse().value
    none.personIds shouldBe empty
    none.locationIds shouldBe empty

    val one = browse(fields.PERSON_ID -> "alice", fields.LOCATION_ID -> "lisbon").value
    one.personIds shouldBe Set("alice")
    one.locationIds shouldBe Set("lisbon")

    val several = browse(
      fields.PERSON_ID -> "alice,bob,carol",
      fields.LOCATION_ID -> "lisbon,sintra",
      fields.FOLDER_ID -> "trips",
      fields.ALBUM_ID -> "best").value
    several.personIds shouldBe Set("alice", "bob", "carol")
    several.locationIds shouldBe Set("lisbon", "sintra")
    several.folderIds shouldBe Set("trips")
    several.albumIds shouldBe Set("best")

    browse(fields.PERSON_ID -> "alice,alice").value.personIds shouldBe Set("alice")
  }

  test("More than 10 IDs in one parameter are refused") {

    /**
     * Setup:
     *
     * Requests naming 10 and 11 people, and 10 and 11 Locations.
     *
     * Assertions:
     *
     * Ten IDs in a parameter are taken, and eleven are refused with a message saying how many a search takes.
     *
     * Edge cases:
     *
     * Ten people together with ten Locations are taken: the bound is each parameter's own. Eleven values that are ten IDs, one of
     * them repeated, are taken.
     */
    browse(fields.PERSON_ID -> ids(10)).value.personIds should have size 10
    browse(fields.LOCATION_ID -> ids(10)).value.locationIds should have size 10
    browse(fields.PERSON_ID -> ids(10), fields.LOCATION_ID -> ids(10)).isRight shouldBe true
    browse(fields.PERSON_ID -> s"${ids(10)},id-1").value.personIds should have size 10

    browse(fields.PERSON_ID -> ids(11)).left.value should include("10 people")
    browse(fields.LOCATION_ID -> ids(11)).left.value should include("10 Locations")
  }

  test("Several people match all of them unless the request says any") {

    /**
     * Setup:
     *
     * Requests naming two people with no `peopleMatch`, with `all`, with `any`, and with values that are neither.
     *
     * Assertions:
     *
     * Left out, the people match is all; `all` and `any` are taken as given, and the query the scope builds carries the match.
     *
     * Edge cases:
     *
     * Any other value, empty or in another case included, is refused with a message naming the parameter.
     */
    val people = fields.PERSON_ID -> "alice,bob"
    browse(people).value.peopleMatch shouldBe PeopleMatch.All
    browse(people, fields.PEOPLE_MATCH -> "all").value.peopleMatch shouldBe PeopleMatch.All
    val any = browse(people, fields.PEOPLE_MATCH -> "any").value
    any.peopleMatch shouldBe PeopleMatch.Any
    any.query().peopleMatch shouldBe PeopleMatch.Any

    for (value <- List("some", "", "ANY")) withClue(s"'$value': ") {
      browse(people, fields.PEOPLE_MATCH -> value).left.value should include(fields.PEOPLE_MATCH)
    }
  }

  test("The four dates are ISO days that make the Date Taken and the Date Imported range") {

    /**
     * Setup:
     *
     * Requests with both ends of each range, with one end of a range alone, with one day as both ends, and with no date.
     *
     * Assertions:
     *
     * `takenFrom` and `takenTo` make the Date Taken range and `importedFrom` and `importedTo` the Date Imported range, each end
     * the day its parameter spells, and the query the scope builds carries both ranges.
     *
     * Edge cases:
     *
     * A range with one end alone is open at the other, one day as both ends is that single day, and a request with no date has no
     * range.
     */
    def day(iso: String) = Some(LocalDate.parse(iso))

    val both = browse(
      fields.TAKEN_FROM -> "2024-06-01",
      fields.TAKEN_TO -> "2024-06-30",
      fields.IMPORTED_FROM -> "2025-01-02",
      fields.IMPORTED_TO -> "2025-01-03").value
    both.takenRange shouldBe Some(DateRange(day("2024-06-01"), day("2024-06-30")))
    both.importedRange shouldBe Some(DateRange(day("2025-01-02"), day("2025-01-03")))
    both.query().takenRange shouldBe both.takenRange
    both.query().importedRange shouldBe both.importedRange

    browse(fields.TAKEN_FROM -> "2024-06-01").value.takenRange shouldBe Some(DateRange(day("2024-06-01"), None))
    browse(fields.IMPORTED_TO -> "2025-01-03").value.importedRange shouldBe Some(DateRange(None, day("2025-01-03")))
    browse(fields.TAKEN_FROM -> "2024-06-01", fields.TAKEN_TO -> "2024-06-01").value.takenRange shouldBe
      Some(DateRange(day("2024-06-01"), day("2024-06-01")))

    val none = browse().value
    none.takenRange shouldBe None
    none.importedRange shouldBe None
  }

  test("A malformed date and a range whose start is after its end are refused") {

    /**
     * Setup:
     *
     * Requests with each of the four dates malformed, and with each range's start a day after its end.
     *
     * Assertions:
     *
     * A date that is not an ISO day is refused with a message naming its parameter, and a range that starts after it ends with a
     * message naming both of its parameters.
     *
     * Edge cases:
     *
     * An empty date, a date without its leading zeros, a day the calendar does not have and a date with a time are all malformed.
     */
    for (
      name <- List(fields.TAKEN_FROM, fields.TAKEN_TO, fields.IMPORTED_FROM, fields.IMPORTED_TO);
      value <- List("", "june", "2024-6-1", "2024-02-30", "2024-06-01T10:00:00")
    ) withClue(s"$name=$value: ") {
      browse(name -> value).left.value should include(name)
    }

    val taken = browse(fields.TAKEN_FROM -> "2024-06-02", fields.TAKEN_TO -> "2024-06-01").left.value
    taken should include(fields.TAKEN_FROM)
    taken should include(fields.TAKEN_TO)
    val imported = browse(fields.IMPORTED_FROM -> "2024-06-02", fields.IMPORTED_TO -> "2024-06-01").left.value
    imported should include(fields.IMPORTED_FROM)
    imported should include(fields.IMPORTED_TO)
  }

  test("A date range or several IDs with the trash view are refused, as Search text is") {

    /**
     * Setup:
     *
     * Requests of the trash view with a Date Taken range, with a Date Imported range, with two people and with two Locations, and
     * the same requests of the repository and the triage view.
     *
     * Assertions:
     *
     * In the trash each of the four is refused with a message naming the trash; the other two views take them.
     *
     * Edge cases:
     *
     * The trash still takes one person, one Location, one folder and one album, and a range open at an end is refused there as a
     * closed one is.
     */
    val advanced = List(
      List(fields.TAKEN_FROM -> "2024-06-01", fields.TAKEN_TO -> "2024-06-30"),
      List(fields.TAKEN_TO -> "2024-06-30"),
      List(fields.IMPORTED_FROM -> "2024-06-01"),
      List(fields.PERSON_ID -> "alice,bob"),
      List(fields.LOCATION_ID -> "lisbon,sintra")
    )

    for (params <- advanced) withClue(s"$params: ") {
      parse(Const.Search.View.TRASHBIN, params*).left.value should include("trash")
      parse(Const.Search.View.DEFAULT, params*).isRight shouldBe true
      parse(Const.Search.View.TRIAGE, params*).isRight shouldBe true
    }

    val single = parse(
      Const.Search.View.TRASHBIN,
      fields.PERSON_ID -> "alice",
      fields.LOCATION_ID -> "lisbon",
      fields.FOLDER_ID -> "trips",
      fields.ALBUM_ID -> "best").value
    single.personIds shouldBe Set("alice")
    single.locationIds shouldBe Set("lisbon")
  }

  test("triaged=true adds the triaged assets to an advanced search") {

    /**
     * Setup:
     *
     * A request of the repository view with two people, two Locations and both date ranges, with and without `triaged=true`.
     *
     * Assertions:
     *
     * Without the flag the scope leaves the triaged assets out; with it the scope has no rule on triage, so the triaged assets
     * are searched too, and it keeps every filter.
     */
    val filters = List(
      fields.PERSON_ID -> "alice,bob",
      fields.LOCATION_ID -> "lisbon,sintra",
      fields.TAKEN_FROM -> "2024-06-01",
      fields.IMPORTED_TO -> "2025-01-03")

    browse(filters*).value.params shouldBe Map(FieldConst.Asset.IS_RECYCLED -> false, FieldConst.Asset.IS_TRIAGED -> false)

    val withTriaged = browse((filters :+ (fields.TRIAGED -> "true"))*).value
    withTriaged.params shouldBe Map(FieldConst.Asset.IS_RECYCLED -> false)
    withTriaged.personIds should have size 2
    withTriaged.locationIds should have size 2
    withTriaged.takenRange shouldBe defined
    withTriaged.importedRange shouldBe defined
  }
}
