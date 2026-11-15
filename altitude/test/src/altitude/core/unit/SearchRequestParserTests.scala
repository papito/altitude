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
        sort = values.get(fields.SORT),
        groupBy = values.get(fields.GROUP_BY),
        groupDirection = values.get(fields.GROUP_DIRECTION),
        layout = values.get(fields.LAYOUT)
      ))
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
     * A parameter left out is no filter, one ID is a list of one, and a comma-separated value is its IDs in the order given,
     * which the query the scope builds takes as a set; the folder and the album stay one ID each.
     *
     * Edge cases:
     *
     * An ID repeated in a parameter counts once, at its first place.
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
     * Requests whose `personId` or `locationId` is empty, a lone comma, one ID followed by a comma, and IDs with empty segments
     * before, between and after them.
     *
     * Assertions:
     *
     * An empty segment is no ID: a parameter of nothing but empty segments names nothing, as one left out does, and the IDs
     * around an empty segment are taken in the order given.
     *
     * Edge cases:
     *
     * Ten IDs followed by a comma are ten IDs, not eleven, so they are taken.
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
     * Requests of the repository view with an album, and with a Triage subset, each beside what only the advanced search panel
     * combines: a date range, two people, two Locations, and two of the Search text, a folder, a person and a Location.
     *
     * Assertions:
     *
     * Each is refused with a message naming the album and the subset: an advanced search searches the view by the panel's filters
     * alone.
     *
     * Edge cases:
     *
     * An album or a subset beside one of the Search text, a folder, a person or a Location, and the same filters with neither an
     * album nor a subset, are taken.
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
     * An empty date, a date without its leading zeros, a day the calendar does not have, a date with a time and a year that is
     * not four digits, signed as ISO 8601 writes a longer one, are all malformed.
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
     * Requests of the trash view with a Date Taken range, with a Date Imported range, with two people and with two Locations, and
     * the same requests of the repository and the triage view.
     *
     * Assertions:
     *
     * In the trash each of the four is refused with a message naming the trash; the other two views take them.
     *
     * Edge cases:
     *
     * The trash still takes one person, one Location or one folder, each alone or beside an album, and a range open at an end is
     * refused there as a closed one is.
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

  test("A description is read into the scope beside the Search filters, each read as it is without one") {

    /**
     * Setup:
     *
     * Requests of the repository view with a description: alone, with spaces and a line feed around it, with the grid layout
     * named, and beside each Search filter in turn (the Search text, a folder, a person, several people matching any, several
     * Locations, each end of the two date ranges, `triaged` true and false), then beside all of them at once.
     *
     * Assertions:
     *
     * Alone, the scope holds the description as written but for the white space around it, no Search text and no filter, and
     * leaves the triaged assets out. Beside filters, the scope is the one the same request has without the description, with the
     * description added: the filters are read the same, and the query the scope builds carries them.
     *
     * Edge cases:
     *
     * A description of 300 characters is taken, and so is one that would parse to no Search term, since a description is not read
     * as Search text. Search text with no usable term beside a description is no text, as it is without one. A request without a
     * description has none in its scope.
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
     * Requests with an empty description, with one of spaces and line feeds alone, with one of 301 characters, and with one of
     * 300 characters between spaces; and a blank description beside a folder.
     *
     * Assertions:
     *
     * The empty and the blank description are refused as blank, alone or beside a filter, and the one of 301 characters as too
     * long, naming the bound.
     *
     * Edge cases:
     *
     * The length is the trimmed description's: 300 characters between spaces are taken.
     */
    browse(fields.DESCRIBE -> "").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> " \n\t ").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> " ", fields.FOLDER_ID -> "trips").left.value shouldBe "A description cannot be blank"
    browse(fields.DESCRIBE -> "a" * 301).left.value shouldBe "A description has at most 300 characters"
    (browse(fields.DESCRIBE -> (" " + "a" * 300 + " ")).value.description.value should have).length(300)
  }

  test("A description takes no album, subset, map area, sort, grouping or map layout, and does not search the trash") {

    /**
     * Setup:
     *
     * Requests of the repository view with a description beside each parameter a Describe search does not take, in turn: an
     * album, a Triage subset, a map area, a sort, a grouping, a group direction and the map layout; a request with a sort and an
     * album beside the description and a folder; and requests with a description in the trash.
     *
     * Assertions:
     *
     * Each is refused, and the message names the parameter a description takes none of; the request with two of them names both,
     * in the order the parameters are declared, and not the folder, which a description takes. A description is refused in the
     * trash, which the message names.
     *
     * Edge cases:
     *
     * Such a parameter is refused whatever its value: the similarity sort itself, a subset that is none. With a filter that would
     * be refused on its own beside the description too, the parameter the description does not take is what is refused.
     */
    val refused = List(
      fields.ALBUM_ID -> "best",
      fields.SUBSET -> "noLocation",
      fields.SUBSET -> "nothing",
      fields.BBOX -> "1,2,3,4",
      fields.SORT -> "created_at1",
      fields.SORT -> Const.Search.SORT_RELEVANCE,
      fields.SORT -> Const.Search.SORT_SIMILARITY,
      fields.GROUP_BY -> "dateTaken",
      fields.GROUP_DIRECTION -> "asc"
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
      fields.SORT -> "filename0",
      fields.FOLDER_ID -> "trips",
      fields.ALBUM_ID -> "best").left.value shouldBe "A description takes no albumId, sort"
    browse(fields.DESCRIBE -> "a cactus", fields.SORT -> "filename0", fields.TAKEN_FROM -> "not a date").left.value shouldBe
      "A description takes no sort"

    parse(Const.Search.View.TRASHBIN, fields.DESCRIBE -> "a cactus").left.value shouldBe "A description does not search the trash"
    parse(Const.Search.View.TRASHBIN, fields.DESCRIBE -> "a cactus", fields.QUERY_TEXT -> "beach").left.value shouldBe
      "A description does not search the trash"
  }

  test("A description searches the Triage view, among the triaged assets and beside the filters that view takes") {

    /**
     * Setup:
     *
     * Requests of the Triage view with a description: alone, with `triaged=true`, and beside the Search text, two people matching
     * any, two Locations and both date ranges at once.
     *
     * Assertions:
     *
     * Each scope is the one the same request has without the description, with the description added: it holds the triaged assets
     * out of the trash alone, and the query it builds carries the filters.
     *
     * Edge cases:
     *
     * What a description takes in no view is refused in the Triage view too, named by the message: a Triage subset, a sort, the
     * Sequence grouping and the map layout. `triaged=false` is refused with the Triage view, with a description as without one.
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

    val refused = List(
      fields.SUBSET -> "unknownPeople",
      fields.SORT -> "created_at1",
      fields.GROUP_BY -> "sequence"
    )
    for (param <- refused) withClue(s"$param: ") {
      parse(triage, fields.DESCRIBE -> "a cactus", param).left.value shouldBe s"A description takes no ${param._1}"
    }
    parse(triage, fields.DESCRIBE -> "a cactus", fields.LAYOUT -> Const.Search.Layout.MAP).left.value shouldBe
      "A description takes no layout=map"
    parse(triage, fields.DESCRIBE -> "a cactus", fields.TRIAGED -> "false").left.value shouldBe
      "The triage view holds only triaged assets"
  }

  test("A filter beside a description is refused as it is without one") {

    /**
     * Setup:
     *
     * Requests of the repository view with a description beside a filter that is refused on its own: eleven people, eleven
     * Locations, an unknown people match, a date that is no ISO day, and a range whose first day is after its last.
     *
     * Assertions:
     *
     * Each is refused with the message the same request has without the description.
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
