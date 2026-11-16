package altitude.core.unit

import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.SearchRequestParser.Scope
import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchQuery

/** The parameters of an advanced search as `SearchRequestParser.parse` reads them into a scope */
@DoNotDiscover class SearchRequestParserTests extends funsuite.AnyFunSuite {

  private val fields = Api.Field.Search

  /** A request of `view` with the given parameters, by their names in the query string */
  private def parse(view: String, params: (String, String)*): Either[String, Scope] = {
    val values = params.toMap
    SearchRequestParser.parse(
      SearchRequestParser.Params(
        view = view,
        q = values.get(fields.QUERY_TEXT),
        folderId = values.get(fields.FOLDER_ID),
        personId = values.get(fields.PERSON_ID),
        albumId = values.get(fields.ALBUM_ID),
        locationId = values.get(fields.LOCATION_ID),
        peopleMatch = values.get(fields.PEOPLE_MATCH),
        takenFrom = values.get(fields.TAKEN_FROM),
        takenTo = values.get(fields.TAKEN_TO),
        importedFrom = values.get(fields.IMPORTED_FROM),
        importedTo = values.get(fields.IMPORTED_TO),
        bbox = values.get(fields.BBOX),
        triaged = values.get(fields.TRIAGED).map(_.toBoolean),
        subset = values.get(fields.SUBSET),
        describe = values.get(fields.DESCRIBE),
        layout = values.get(fields.LAYOUT)
      ))
  }

  private def browse(params: (String, String)*): Either[String, Scope] = parse(Const.Search.View.DEFAULT, params*)

  private def ids(count: Int): String = (1 to count).map(n => s"id-$n").mkString(",")

  test("A person or Location parameter names one ID or several, separated by commas") {

    /**
     * Setup:
     *
     * Requests naming no person or Location, one of each, and several of each.
     *
     * Assertions:
     *
     * A parameter left out is no filter, and a comma-separated value is its IDs in the order given, which the built query takes
     * as a set. The folder and the album stay one ID each.
     *
     * Edge cases:
     *
     * A repeated ID counts once, at its first place.
     */
    val none = browse().value
    none.personIds shouldBe empty
    none.locationIds shouldBe empty

    val one = browse(fields.PERSON_ID -> "alice", fields.LOCATION_ID -> "lisbon").value
    one.personIds shouldBe List("alice")
    one.locationIds shouldBe List("lisbon")

    val several = browse(fields.PERSON_ID -> "carol,alice,bob", fields.LOCATION_ID -> "sintra,lisbon").value
    several.personIds shouldBe List("carol", "alice", "bob")
    several.locationIds shouldBe List("sintra", "lisbon")
    several.query().personIds shouldBe Set("alice", "bob", "carol")
    several.query().locationIds shouldBe Set("lisbon", "sintra")

    val places = browse(fields.FOLDER_ID -> "trips", fields.ALBUM_ID -> "best").value
    places.folderIds shouldBe Set("trips")
    places.albumIds shouldBe Set("best")

    browse(fields.PERSON_ID -> "bob,alice,bob").value.personIds shouldBe List("bob", "alice")
  }

  test("Empty segments of a person or Location parameter name nothing") {

    /**
     * Setup:
     *
     * Person and Location parameters that are empty, only commas, or IDs with empty segments around them.
     *
     * Assertions:
     *
     * An empty segment is no ID: only empty segments name nothing, and the IDs around them keep their order.
     *
     * Edge cases:
     *
     * Ten IDs followed by a comma are ten, within the bound.
     */
    for (value <- List("", ",", ",,")) withClue(s"'$value': ") {
      val scope = browse(fields.PERSON_ID -> value, fields.LOCATION_ID -> value).value
      scope.personIds shouldBe empty
      scope.locationIds shouldBe empty
    }

    browse(fields.PERSON_ID -> "alice,").value.personIds shouldBe List("alice")
    browse(fields.LOCATION_ID -> ",lisbon,,sintra,").value.locationIds shouldBe List("lisbon", "sintra")
    browse(fields.PERSON_ID -> s"${ids(10)},").value.personIds should have size 10
  }

  test("An album or a Triage subset with the filters an advanced search combines is refused") {

    /**
     * Setup:
     *
     * Requests with an album or a Triage subset beside filters only the advanced search combines: a date range, several people or
     * Locations, or two of the Search text, a folder, a person and a Location.
     *
     * Assertions:
     *
     * Each is refused with a message naming the album and the subset.
     *
     * Edge cases:
     *
     * An album or a subset beside a single one of those filters is taken, and so are the combined filters alone.
     */
    val combined = List(
      List(fields.TAKEN_FROM -> "2024-06-01"),
      List(fields.IMPORTED_TO -> "2024-06-30"),
      List(fields.PERSON_ID -> "alice,bob"),
      List(fields.LOCATION_ID -> "lisbon,sintra"),
      List(fields.QUERY_TEXT -> "beach", fields.FOLDER_ID -> "trips"),
      List(fields.PERSON_ID -> "alice", fields.LOCATION_ID -> "lisbon")
    )
    val scopes = List(fields.ALBUM_ID -> "best", fields.SUBSET -> "noLocation")

    for (filters <- combined; scope <- scopes) withClue(s"$filters with $scope: ") {
      val refusal = browse((filters :+ scope)*).left.value
      refusal should include("album")
      refusal should include("subset")
    }
    for (filters <- combined) withClue(s"$filters: ")(browse(filters*).isRight shouldBe true)

    val single =
      List(fields.QUERY_TEXT -> "beach", fields.FOLDER_ID -> "trips", fields.PERSON_ID -> "alice", fields.LOCATION_ID -> "lisbon")
    for (filter <- single; scope <- scopes) withClue(s"$filter with $scope: ")(browse(filter, scope).isRight shouldBe true)
  }

  test("More than 10 IDs in one parameter are refused") {

    /**
     * Setup:
     *
     * Requests naming 10 and 11 people, and 10 and 11 Locations.
     *
     * Assertions:
     *
     * Ten IDs are taken; eleven are refused with a message giving the bound.
     *
     * Edge cases:
     *
     * The bound is per parameter (ten people with ten Locations are taken) and counts distinct IDs (ten plus a repeat are taken).
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
     * Requests naming two people with no `peopleMatch`, with `all`, with `any`, and with other values.
     *
     * Assertions:
     *
     * The match defaults to all, `all` and `any` are taken, and the built query carries the match.
     *
     * Edge cases:
     *
     * Any other value, including an empty or uppercase one, is refused with a message naming the parameter.
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
     * Requests with both ends of each range, one end alone, one day as both ends, and no date.
     *
     * Assertions:
     *
     * `takenFrom`/`takenTo` make the Date Taken range and `importedFrom`/`importedTo` the Date Imported range, and the built
     * query carries both.
     *
     * Edge cases:
     *
     * A lone end leaves the range open at the other, one day as both ends is that day, and no date is no range.
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
     * Each of the four dates malformed, and each range's start a day after its end.
     *
     * Assertions:
     *
     * A date that is not an ISO day is refused naming its parameter, and a reversed range naming both of its parameters.
     *
     * Edge cases:
     *
     * Empty, unpadded, nonexistent and timed dates are malformed, and so is a signed year of other than four digits.
     */
    for (
      name <- List(fields.TAKEN_FROM, fields.TAKEN_TO, fields.IMPORTED_FROM, fields.IMPORTED_TO);
      value <- List("", "june", "2024-6-1", "2024-02-30", "2024-06-01T10:00:00", "+10000-01-01", "-0001-06-01")
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
     * Requests of the trash, repository and triage views with a date range, two people or two Locations.
     *
     * Assertions:
     *
     * The trash refuses each with a message naming the trash; the other views take them.
     *
     * Edge cases:
     *
     * The trash still takes one person, Location or folder, alone or beside an album, and refuses an open range as a closed one.
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

    for (
      single <- List(fields.PERSON_ID -> "alice", fields.LOCATION_ID -> "lisbon", fields.FOLDER_ID -> "trips");
      album <- List(Nil, List(fields.ALBUM_ID -> "best"))
    ) withClue(s"$single $album: ")(parse(Const.Search.View.TRASHBIN, (single +: album)*).isRight shouldBe true)
  }

  test("triaged=true adds the triaged assets to an advanced search") {

    /**
     * Setup:
     *
     * A request with several people, Locations and date ranges, with and without `triaged=true`.
     *
     * Assertions:
     *
     * Without the flag the scope leaves triaged assets out; with it the scope drops the triage rule and keeps every filter.
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

  test("A description is read into the scope beside the Search filters, each read as it is without one") {

    /**
     * Setup:
     *
     * Requests with a description: alone, padded with white space, with the grid layout, beside each Search filter in turn, and
     * beside all of them.
     *
     * Assertions:
     *
     * Alone, the scope holds the trimmed description and no filter, and leaves triaged assets out. Beside filters, the scope is
     * the one without the description plus the description, and its query carries the filters.
     *
     * Edge cases:
     *
     * A description of 300 characters is taken, and so is one that would parse to no Search term, since it is not Search text.
     * Unusable Search text beside a description is no text. A request without a description has none.
     */
    val scope = browse(fields.DESCRIBE -> "woman in a park").value
    scope shouldBe browse().value.copy(description = Some("woman in a park"))
    scope.text shouldBe None
    scope.folderIds shouldBe empty
    scope.personIds shouldBe empty
    scope.locationIds shouldBe empty
    scope.takenRange shouldBe None
    scope.importedRange shouldBe None
    scope.params shouldBe Map(FieldConst.Asset.IS_RECYCLED -> false, FieldConst.Asset.IS_TRIAGED -> false)

    browse(fields.DESCRIBE -> "  woman in\na park \n").value.description shouldBe Some("woman in\na park")
    browse(fields.DESCRIBE -> "a cactus", fields.LAYOUT -> Const.Search.Layout.GRID).value.description shouldBe Some("a cactus")

    val filters = List(
      List(fields.QUERY_TEXT -> "beach -sunset"),
      List(fields.FOLDER_ID -> "trips"),
      List(fields.PERSON_ID -> "alice"),
      List(fields.PERSON_ID -> "bob,alice", fields.PEOPLE_MATCH -> "any"),
      List(fields.LOCATION_ID -> "sintra,lisbon"),
      List(fields.TAKEN_FROM -> "2024-06-01"),
      List(fields.TAKEN_TO -> "2024-06-30"),
      List(fields.IMPORTED_FROM -> "2025-01-08"),
      List(fields.IMPORTED_TO -> "2025-01-09"),
      List(fields.TRIAGED -> "true"),
      List(fields.TRIAGED -> "false")
    )
    for (beside <- filters :+ filters.init.flatten) withClue(s"$beside: ") {
      val described = browse((fields.DESCRIBE -> "a cactus") +: beside*).value
      described shouldBe browse(beside*).value.copy(description = Some("a cactus"))
    }

    val all = browse((fields.DESCRIBE -> "a cactus") +: filters.init.flatten*).value
    all.text shouldBe Some("beach -sunset")
    all.query().text shouldBe Some("beach -sunset")
    all.query().folderIds shouldBe Set("trips")
    all.query().personIds shouldBe Set("alice", "bob")
    all.query().peopleMatch shouldBe PeopleMatch.Any
    all.query().locationIds shouldBe Set("lisbon", "sintra")
    all.query().takenRange shouldBe Some(DateRange(Some(LocalDate.parse("2024-06-01")), Some(LocalDate.parse("2024-06-30"))))
    all.query().importedRange shouldBe Some(DateRange(Some(LocalDate.parse("2025-01-08")), Some(LocalDate.parse("2025-01-09"))))
    all.params shouldBe Map(FieldConst.Asset.IS_RECYCLED -> false)

    (browse(fields.DESCRIBE -> "a" * 300).value.description.value should have).length(300)
    browse(fields.DESCRIBE -> "-").value.description shouldBe Some("-")
    browse(fields.DESCRIBE -> "a cactus", fields.QUERY_TEXT -> "-").value.text shouldBe None
    browse(fields.QUERY_TEXT -> "beach").value.description shouldBe None
  }

  test("A blank description and one longer than 300 characters are refused") {

    /**
     * Setup:
     *
     * Empty, white-space-only, 301-character and space-padded 300-character descriptions; a blank one beside a folder.
     *
     * Assertions:
     *
     * Empty and blank descriptions are refused as blank, even beside a filter, and 301 characters as too long.
     *
     * Edge cases:
     *
     * The bound counts the trimmed description.
     */
    browse(fields.DESCRIBE -> "").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> " \n\t ").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> " ", fields.FOLDER_ID -> "trips").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> "a" * 301).left.value shouldBe "A description has at most 300 characters"
    (browse(fields.DESCRIBE -> (" " + "a" * 300 + " ")).value.description.value should have).length(300)
  }

  test("A description takes no album, subset, map area or map layout, and does not search the trash") {

    /**
     * Setup:
     *
     * A description beside an album, a Triage subset, a map area or the map layout; beside two of them and a folder; and in the
     * trash.
     *
     * Assertions:
     *
     * Each is refused naming the parameters a description does not take, in declaration order, never the folder. In the trash the
     * message names the trash.
     *
     * Edge cases:
     *
     * An unknown subset is refused too. When another filter is also invalid, the parameter a description does not take is the one
     * reported.
     */
    val refused = List(
      fields.ALBUM_ID -> "best",
      fields.SUBSET -> "noLocation",
      fields.SUBSET -> "nothing",
      fields.BBOX -> "1,2,3,4"
    )
    for (param <- refused) withClue(s"$param: ") {
      browse(fields.DESCRIBE -> "a cactus", param).left.value shouldBe s"A description takes no ${param._1}"
      browse(fields.DESCRIBE -> "a cactus", fields.QUERY_TEXT -> "beach", param).left.value shouldBe
        s"A description takes no ${param._1}"
    }

    browse(fields.DESCRIBE -> "a cactus", fields.LAYOUT -> Const.Search.Layout.MAP).left.value shouldBe
      "A description takes no layout=map"
    browse(
      fields.DESCRIBE -> "a cactus",
      fields.SUBSET -> "noLocation",
      fields.FOLDER_ID -> "trips",
      fields.ALBUM_ID -> "best").left.value shouldBe "A description takes no albumId, subset"
    browse(fields.DESCRIBE -> "a cactus", fields.BBOX -> "1,2,3,4", fields.TAKEN_FROM -> "not a date").left.value shouldBe
      "A description takes no bbox"

    parse(Const.Search.View.TRASHBIN, fields.DESCRIBE -> "a cactus").left.value shouldBe "A description does not search the trash"
    parse(Const.Search.View.TRASHBIN, fields.DESCRIBE -> "a cactus", fields.QUERY_TEXT -> "beach").left.value shouldBe
      "A description does not search the trash"
  }

  test("A description searches the Triage view, among the triaged assets and beside the filters that view takes") {

    /**
     * Setup:
     *
     * Triage-view requests with a description: alone, with `triaged=true`, and beside the Search text, people, Locations and date
     * ranges.
     *
     * Assertions:
     *
     * Each scope is the one without the description plus the description: only triaged assets outside the trash, with the filters
     * in its query.
     *
     * Edge cases:
     *
     * A Triage subset and the map layout are refused here as in any view, and `triaged=false` is refused in this view either way.
     */
    val triage = Const.Search.View.TRIAGE
    val described = parse(triage, fields.DESCRIBE -> "a cactus").value
    described shouldBe parse(triage).value.copy(description = Some("a cactus"))
    described.params shouldBe SearchQuery.TRIAGE_VIEW
    parse(triage, fields.DESCRIBE -> "a cactus", fields.TRIAGED -> "true").value shouldBe described

    val filters = List(
      fields.QUERY_TEXT -> "beach",
      fields.PERSON_ID -> "bob,alice",
      fields.PEOPLE_MATCH -> "any",
      fields.LOCATION_ID -> "sintra,lisbon",
      fields.TAKEN_FROM -> "2024-06-01",
      fields.IMPORTED_TO -> "2025-01-09"
    )
    val narrowed = parse(triage, (fields.DESCRIBE -> "a cactus") +: filters*).value
    narrowed shouldBe parse(triage, filters*).value.copy(description = Some("a cactus"))
    narrowed.params shouldBe SearchQuery.TRIAGE_VIEW
    narrowed.query().text shouldBe Some("beach")
    narrowed.query().personIds shouldBe Set("alice", "bob")
    narrowed.query().locationIds shouldBe Set("lisbon", "sintra")

    parse(triage, fields.DESCRIBE -> "a cactus", fields.SUBSET -> "unknownPeople").left.value shouldBe
      "A description takes no subset"
    parse(triage, fields.DESCRIBE -> "a cactus", fields.LAYOUT -> Const.Search.Layout.MAP).left.value shouldBe
      "A description takes no layout=map"
    parse(triage, fields.DESCRIBE -> "a cactus", fields.TRIAGED -> "false").left.value shouldBe
      "The triage view holds only triaged assets"
  }

  test("A filter beside a description is refused as it is without one") {

    /**
     * Setup:
     *
     * A description beside each filter that is refused on its own: too many people or Locations, an unknown people match, a
     * malformed date and a reversed range.
     *
     * Assertions:
     *
     * Each is refused with the same message as without the description.
     */
    val malformed = List(
      List(fields.PERSON_ID -> ids(11)) -> "A search names at most 10 people",
      List(fields.LOCATION_ID -> ids(11)) -> "A search names at most 10 Locations",
      List(fields.PERSON_ID -> "alice,bob", fields.PEOPLE_MATCH -> "some") -> "Unknown peopleMatch value",
      List(fields.TAKEN_FROM -> "June 2024") -> "takenFrom must be a date, as 2024-06-01",
      List(fields.IMPORTED_TO -> "2024-02-30") -> "importedTo must be a date, as 2024-06-01"
    )
    for ((params, message) <- malformed) withClue(s"$params: ") {
      browse(params*).left.value shouldBe message
      browse((fields.DESCRIBE -> "a cactus") +: params*).left.value shouldBe message
    }

    val reversed = List(fields.TAKEN_FROM -> "2024-06-30", fields.TAKEN_TO -> "2024-06-01")
    browse((fields.DESCRIBE -> "a cactus") +: reversed*).left.value shouldBe browse(reversed*).left.value
  }
}
