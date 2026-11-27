package altitude.core.integration

import altitude.test.IntegrationTestUtil.withJvmTimeZone
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldNot }
import org.scalatest.tagobjects.Slow
import org.slf4j.LoggerFactory
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.language.reflectiveCalls
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.models.*
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.service.FaceDetectionService
import altitude.core.service.PersonService
import altitude.core.util.*

@DoNotDiscover class SearchServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with SearchPlans
  with TextSearchPaths {

  test("Index and search by term") {

    /**
     * Setup:
     *
     * Two assets with values in two KEYWORD fields and a TEXT field; only "Teri Hatcher" is in both "cast" fields.
     *
     * Assertions:
     *
     * Search text finds an asset by its metadata values: a name in one cast finds one asset, the shared name finds both.
     *
     * Edge cases:
     *
     * An upper-case term ("TERI") matches.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "quotes", fieldType = FieldType.TEXT))

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = "cast", fieldType = FieldType.KEYWORD))

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("picture", "man", "office", "monday", "how is this my life?"),
      field2.persistedId -> Set(
        """
          We have blueberry, raspberry, ginseng, sleepy time, green tea,
          green tea with lemon, green tea with lemon and honey, liver disaster,
          ginger with honey, ginger without honey, vanilla almond, white truffel,
          blueberry chamomile, vanilla walnut, constant comment and... earl grey.
          """,
        """
          Ok this next song goes out to the guy who keeps yelling from the balcony.
          It's called "We Hate You, Please Die."
          """,
        """
          I partake not in the meat, nor the breast milk, nor the ovum, of any creature, with a face.
          """
      ),
      field3.persistedId -> Set("Lindsay Lohan", "Conan O'Brien", "Teri Hatcher", "Sam Rockwell")
    )

    testContext.persistAsset(metadata = UserMetadata(data))

    data = Map[String, Set[String]](
      field1.persistedId -> Set("tree", "shoe", "desert", "California"),
      field2.persistedId -> Set(
        """
          “If I ever start referring to these as the best years of my life — remind me to kill myself.”
          """,
        """
          George Washington was in a cult, and that cult was into aliens, man.
          """,
        """
          I’d like to stop thinking of the present as some minor, insignificant preamble to something else.
          """
      ),
      field3.persistedId -> Set("Keanu Reeves", "Sandra Bullock", "Dennis Hopper", "Teri Hatcher")
    )

    testContext.persistAsset(metadata = UserMetadata(data))

    var results: SearchResult = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("keanu")))
    results.nonEmpty shouldBe true
    results.total shouldBe Some(1)

    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("TERI")))
    results.nonEmpty shouldBe true
    results.total shouldBe Some(2)
  }

  test("Filter by folder") {

    /**
     * Setup:
     *
     * A folder with a subfolder, and three assets in each with the same keyword metadata.
     *
     * Assertions:
     *
     * A text search scoped to the subfolder counts its three assets, one scoped to the parent folder counts the subfolder's too,
     * and a search with neither a folder nor text counts all six.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("space", "force", "tactical", "pants")
    )

    val metadata = UserMetadata(data)

    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1_1), metadata = metadata))
    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1), metadata = metadata))

    val qFolder1_1 = new SearchQuery(rpp = PAGE_SIZE, text = Some("space"), folderIds = Set(folder1_1.persistedId))
    var results: SearchResult = search(qFolder1_1)
    results.total shouldBe Some(3)

    val qFolder1 = new SearchQuery(rpp = PAGE_SIZE, text = Some("space"), folderIds = Set(folder1.persistedId))
    results = search(qFolder1)
    results.total shouldBe Some(6)

    val qAllFolders = new SearchQuery(rpp = PAGE_SIZE)
    results = search(qAllFolders)
    results.total shouldBe Some(6)

  }

  test("Searching with root folder ID includes triaged assets") {

    /**
     * Setup:
     *
     * Two assets in a folder and one triaged asset, which has no folder.
     *
     * Assertions:
     *
     * A search scoped to the root folder counts all three, the triaged asset included.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    (1 to 2).foreach(_ => testContext.persistAsset(folder = Some(folder1)))
    testContext.persistAsset(isTriaged = true)

    val rootFolderId = testContext.repository.rootFolderId

    val results = search(new SearchQuery(rpp = PAGE_SIZE, folderIds = Set(rootFolderId)))
    results.total shouldBe Some(3)
  }

  def fixtureForPersonFilter: Object { val assetsPerPersonCount: Int; val people: Seq[Person] } = new {
    val peopleCount = 3
    val assetsPerPersonCount = 3

    val people: Seq[Person] = (1 to peopleCount).map {
      _ =>
        val person = testApp.service.person.addPerson(Person())
        (1 to assetsPerPersonCount).foreach(_ => testContext.addTestFacesAndAssets(person))

        person
    }
  }

  test("Filter by one person") {

    /**
     * Setup:
     *
     * Three people, each with a Face in three assets of their own (`fixtureForPersonFilter`).
     *
     * Assertions:
     *
     * Filtering by one person counts that person's three assets only.
     */
    val f = fixtureForPersonFilter
    val q = new SearchQuery(rpp = PAGE_SIZE, personIds = Set(f.people.head.persistedId))
    val results = search(q)
    results.total shouldBe Some(f.assetsPerPersonCount)
  }

  test("Filter by more than one person") {

    /**
     * Setup:
     *
     * Three people, each with a Face in three assets of their own (`fixtureForPersonFilter`).
     *
     * Assertions:
     *
     * Filtering by any of the three people counts all of their nine assets.
     */
    val f = fixtureForPersonFilter
    val q = new SearchQuery(rpp = PAGE_SIZE, personIds = f.people.map(_.persistedId).toSet, peopleMatch = PeopleMatch.Any)
    val results = search(q)
    results.total shouldBe Some(f.assetsPerPersonCount * f.people.length)
  }

  test("People matched all are the assets every one of them appears in; matched any, those any of them does") {

    /**
     * Setup:
     *
     * Five assets: Alice (twice) and Bob, Alice alone, Bob alone, Alice, Bob and Carol, and no Face. Dave is in no asset.
     *
     * Assertions:
     *
     * Matching all of the people finds the assets every one of them is in, matching any finds those any of them is in, and a
     * search that does not say matches all. The page's total and the exact count agree with the page.
     *
     * Edge cases:
     *
     * An asset with two Faces of one person is found once. One person alone is the same search either way. All of a set with Dave
     * is nothing, while any of it is the others' assets.
     */
    val alice = addPerson("Alice")
    val bob = addPerson("Bob")
    val carol = addPerson("Carol")
    val dave = addPerson("Dave")
    def assetOf(people: Person*): String = {
      val asset = testContext.persistAsset()
      people.foreach(addFace(_, asset))
      asset.persistedId
    }
    val both = assetOf(alice, bob, alice)
    val aliceOnly = assetOf(alice)
    val bobOnly = assetOf(bob)
    val allThree = assetOf(alice, bob, carol)
    assetOf()

    def matching(peopleMatch: PeopleMatch, people: Person*): List[String] = {
      val query = new SearchQuery(rpp = PAGE_SIZE, personIds = people.map(_.persistedId).toSet, peopleMatch = peopleMatch)
      val result = search(query)
      result.total shouldBe Some(result.records.size)
      count(query) shouldBe result.records.size
      result.records.map(_.persistedId).sorted
    }

    matching(PeopleMatch.All, alice, bob) shouldBe List(both, allThree).sorted
    matching(PeopleMatch.Any, alice, bob) shouldBe List(both, aliceOnly, bobOnly, allThree).sorted
    matching(PeopleMatch.All, alice, bob, carol) shouldBe List(allThree)

    val unsaid = new SearchQuery(rpp = PAGE_SIZE, personIds = Set(alice.persistedId, bob.persistedId))
    search(unsaid).records.map(_.persistedId).sorted shouldBe List(both, allThree).sorted

    matching(PeopleMatch.All, alice) shouldBe List(both, aliceOnly, allThree).sorted
    matching(PeopleMatch.Any, alice) shouldBe List(both, aliceOnly, allThree).sorted

    matching(PeopleMatch.All, alice, dave) shouldBe Nil
    matching(PeopleMatch.Any, alice, dave) shouldBe List(both, aliceOnly, allThree).sorted
  }

  test("A Date Taken range holds whole capture days, both ends included or either left open, and never an undated asset") {

    /**
     * Setup:
     *
     * Assets captured at the edges of June 2024 (the last second of May, the first midnight of June, half a second before July,
     * the first midnight of July), one mid-June, and one with no capture time.
     *
     * Assertions:
     *
     * A range holds its end days whole, and an open end holds everything before or after. The page's total and the exact count
     * agree with the page.
     *
     * Edge cases:
     *
     * The undated asset is in no range, not even an open one. The days are the camera's: a server far east or far west of UTC
     * finds the same assets.
     */
    def captured(taken: Option[String]): String = {
      val asset = testContext.persistAsset()
      testContext.setAssetDates(asset.persistedId, taken.map(LocalDateTime.parse), OffsetDateTime.parse("2026-09-07T12:00:00Z"))
      asset.persistedId
    }
    val lastOfMay = captured(Some("2024-05-31T23:59:59"))
    val firstOfJune = captured(Some("2024-06-01T00:00:00"))
    val midJune = captured(Some("2024-06-15T12:00:00"))
    val lastOfJune = captured(Some("2024-06-30T23:59:59.5"))
    val firstOfJuly = captured(Some("2024-07-01T00:00:00"))
    captured(None)

    def taken(range: Option[DateRange]): Set[String] = foundWithin(takenRange = range)

    taken(days("2024-06-01", "2024-06-30")) shouldBe Set(firstOfJune, midJune, lastOfJune)
    taken(days("2024-06-01", "2024-06-01")) shouldBe Set(firstOfJune)
    taken(days(to = "2024-06-01")) shouldBe Set(lastOfMay, firstOfJune)
    taken(days(from = "2024-06-30")) shouldBe Set(lastOfJune, firstOfJuly)

    // The camera's days, wherever the server is
    for (zone <- List("Pacific/Kiritimati", "Pacific/Pago_Pago")) withClue(zone) {
      withJvmTimeZone(zone) {
        taken(days("2024-06-01", "2024-06-30")) shouldBe Set(firstOfJune, midJune, lastOfJune)
      }
    }
  }

  test("A Date Imported range holds whole days of the server's zone, both ends included or either left open") {

    /**
     * Setup:
     *
     * Six undated assets imported just before and at three New York midnights (UTC-4), down to the microsecond.
     *
     * Assertions:
     *
     * On a New York server a range holds whole New York days, though one asset of 1 June there was imported on 2 June by UTC, and
     * an open end holds everything before or after. The page's total and the exact count agree with the page.
     *
     * Edge cases:
     *
     * The same range on a UTC server holds the days of UTC.
     */
    def imported(instant: String): String = {
      val asset = testContext.persistAsset()
      testContext.setAssetDates(asset.persistedId, None, OffsetDateTime.parse(instant))
      asset.persistedId
    }
    val endOfMay = imported("2024-06-01T03:59:59Z")
    val startOfFirst = imported("2024-06-01T04:00:00Z")
    val endOfFirst = imported("2024-06-02T03:59:59.999999Z")
    val startOfSecond = imported("2024-06-02T04:00:00Z")
    val endOfSecond = imported("2024-06-03T03:59:59Z")
    val startOfThird = imported("2024-06-03T04:00:00Z")

    def importedWithin(range: Option[DateRange]): Set[String] = foundWithin(importedRange = range)

    withJvmTimeZone("America/New_York") {
      importedWithin(days("2024-06-01", "2024-06-01")) shouldBe Set(startOfFirst, endOfFirst)
      importedWithin(days("2024-06-01", "2024-06-02")) shouldBe Set(startOfFirst, endOfFirst, startOfSecond, endOfSecond)
      importedWithin(days(to = "2024-06-01")) shouldBe Set(endOfMay, startOfFirst, endOfFirst)
      importedWithin(days(from = "2024-06-02")) shouldBe Set(startOfSecond, endOfSecond, startOfThird)
    }

    // The days of whatever zone the server is in
    withJvmTimeZone("UTC") {
      importedWithin(days("2024-06-01", "2024-06-01")) shouldBe Set(endOfMay, startOfFirst)
    }
  }

  test("A Date Imported range that ends on the last day of year 9999 is open at its end") {

    /**
     * Setup:
     *
     * Assets imported in June 2024 and June 2025, searched on servers in New York, UTC and Tokyo. In the first two the day after
     * 31 December 9999 begins in year 10000, which the engines do not write as they write other years.
     *
     * Assertions:
     *
     * In every zone, a range from 2025 to 31 December 9999 holds the later asset, and one open at its start holds both.
     */
    def imported(instant: String): String = {
      val asset = testContext.persistAsset()
      testContext.setAssetDates(asset.persistedId, None, OffsetDateTime.parse(instant))
      asset.persistedId
    }
    val earlier = imported("2024-06-15T12:00:00Z")
    val later = imported("2025-06-15T12:00:00Z")

    for (zone <- List("America/New_York", "UTC", "Asia/Tokyo")) withClue(s"$zone: ") {
      withJvmTimeZone(zone) {
        foundWithin(importedRange = days("2025-01-01", "9999-12-31")) shouldBe Set(later)
        foundWithin(importedRange = days(to = "9999-12-31")) shouldBe Set(earlier, later)
      }
    }
  }

  test("The people match and each date range narrow a search with text, a folder subtree and a grouping") {

    /**
     * Setup:
     *
     * A search for "beach" in Trips (with its subfolder Lisbon), all of Alice and Bob, captured in June and imported around 1
     * July. Two assets match every filter; five more each miss exactly one (text, folder, people, Date Taken, Date Imported).
     *
     * Assertions:
     *
     * The flat page, the exact count and the page grouped by capture day hold the two assets alone. Relaxing the people match or
     * dropping either date range adds just the asset that missed it, flat and grouped alike.
     *
     * Edge cases:
     *
     * Expanding the folder to its subtree and resolving the text each copy the query; the filters survive the copies.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val lisbon: Folder = testApp.service.folder.add(name = "Lisbon", parentId = trips.id)
    val other: Folder = testApp.service.folder.add("Other")
    val alice = addPerson("Alice")
    val bob = addPerson("Bob")

    def asset(
        fileName: String,
        folder: Folder,
        people: List[Person] = List(alice, bob),
        taken: String = "2024-06-10T10:00:00",
        imported: String = "2024-07-01T12:00:00Z"): String = {
      val added = importAsset(fileName, folder = Some(folder))
      people.foreach(addFace(_, added))
      testContext.setAssetDates(added.persistedId, Some(LocalDateTime.parse(taken)), OffsetDateTime.parse(imported))
      added.persistedId
    }
    val inLisbon = asset("beach-1.jpg", lisbon)
    val inTrips = asset("beach-6.jpg", trips, taken = "2024-06-11T10:00:00")
    asset("lake.jpg", lisbon)
    asset("beach-2.jpg", other)
    val aliceAlone = asset("beach-3.jpg", trips, people = List(alice))
    val inAugust = asset("beach-4.jpg", trips, taken = "2024-08-01T10:00:00")
    val inSeptember = asset("beach-5.jpg", trips, imported = "2024-09-01T12:00:00Z")

    // The import days are a day wider than the import itself, so the server's zone does not decide the test
    def query(
        peopleMatch: PeopleMatch = PeopleMatch.All,
        takenRange: Option[DateRange] = days("2024-06-01", "2024-06-30"),
        importedRange: Option[DateRange] = days("2024-06-30", "2024-07-02"),
        grouping: Option[SearchGrouping] = None): SearchQuery =
      new SearchQuery(
        text = Some("beach"),
        folderIds = Set(trips.persistedId),
        personIds = Set(alice.persistedId, bob.persistedId),
        peopleMatch = peopleMatch,
        takenRange = takenRange,
        importedRange = importedRange,
        rpp = PAGE_SIZE,
        searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
        grouping = grouping
      )
    val byDay = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    def flat(query: SearchQuery): List[String] = search(query).records.map(_.persistedId)
    def grouped(query: SearchQuery): Set[String] = searchGrouped(query).assets.map(_.persistedId).toSet

    flat(query()) shouldBe List(inLisbon, inTrips)
    search(query()).total shouldBe Some(2)
    count(query()) shouldBe 2

    val dayGroups = searchGrouped(query(grouping = byDay))
    dayGroups.groups.map(group => (group.total, group.assets.map(_.persistedId))) shouldBe
      List((1, List(inTrips)), (1, List(inLisbon)))
    dayGroups.total shouldBe Some(2)

    flat(query(peopleMatch = PeopleMatch.Any)) shouldBe List(inLisbon, aliceAlone, inTrips)
    grouped(query(peopleMatch = PeopleMatch.Any, grouping = byDay)) shouldBe Set(inLisbon, aliceAlone, inTrips)

    flat(query(takenRange = None)) shouldBe List(inLisbon, inAugust, inTrips)
    grouped(query(takenRange = None, grouping = byDay)) shouldBe Set(inLisbon, inAugust, inTrips)

    flat(query(importedRange = None)) shouldBe List(inLisbon, inSeptember, inTrips)
    grouped(query(importedRange = None, grouping = byDay)) shouldBe Set(inLisbon, inSeptember, inTrips)
  }

  test("The Unknown people subset matches the assets with a Face of an unnamed, visible, live person, in Triage and Browse") {

    /**
     * Setup:
     *
     * Triaged assets with Faces of: an unnamed person and a named one, the named one alone, an unnamed hidden person, an unnamed
     * bad match, an unnamed person merged into the named one, and nobody. Two sorted assets, with the first unnamed person and
     * with the named one.
     *
     * Assertions:
     *
     * The subset matches the asset with the visible, live unnamed person: the triaged one in the triage view, the sorted one in
     * the repository view, by the flat page, the Location-grouped page, the exact count and the capped count alike.
     *
     * Edge cases:
     *
     * A named person beside the unknown one does not keep the asset out; named, hidden, bad-match or merged-away people alone, or
     * no Face, are no match.
     */
    val unknown = testApp.service.person.addPerson(Person())
    val named = addPerson("Alice")
    val hidden = testApp.service.person.addPerson(Person())
    val discarded = testApp.service.person.addPerson(Person())
    val mergedAway = testApp.service.person.addPerson(Person())

    def triagedWithFacesOf(people: Person*): Asset = {
      val asset = testContext.persistAsset(isTriaged = true)
      people.foreach(addFace(_, asset))
      asset
    }

    val withUnknown = triagedWithFacesOf(unknown, named)
    triagedWithFacesOf(named)
    triagedWithFacesOf(hidden)
    triagedWithFacesOf(discarded)
    triagedWithFacesOf(mergedAway)
    triagedWithFacesOf()
    val sortedWithUnknown = testContext.persistAsset()
    addFace(unknown, sortedWithUnknown)
    addFace(named, testContext.persistAsset())

    testApp.service.person.setVisibility(hidden, isHidden = true)
    testApp.service.person.markAsBadMatch(discarded)
    testApp.service.person.merge(named, mergedAway)

    withClue("triage: ")(subsetMatches(triageView, TriageSubset.UnknownPeople) shouldBe Set(withUnknown.persistedId))
    withClue("browse: ")(subsetMatches(browsingView, TriageSubset.UnknownPeople) shouldBe Set(sortedWithUnknown.persistedId))
  }

  test("The No Location subset matches the assets no Location points at, whatever their own position, in Triage and Browse") {

    /**
     * Setup:
     *
     * A Location holding a triaged and a sorted asset. Outside it: a triaged asset with its own GPS position, a triaged asset
     * without one, and a sorted asset.
     *
     * Assertions:
     *
     * The subset matches the assets outside every Location in each view, by the flat page, the Location-grouped page (one No
     * location group), the exact count and the capped count alike. The map plots the positioned asset alone.
     *
     * Edge cases:
     *
     * A GPS position of its own does not count as a Location.
     */
    val beach = testApp.service.location.addLocation("Beach", 1, 2)
    val triagedInBeach = testContext.persistAsset(isTriaged = true)
    val sortedInBeach = testContext.persistAsset()
    testApp.service.location.addAssets(beach.persistedId, Set(triagedInBeach.persistedId, sortedInBeach.persistedId))
    val positioned = testContext.persistAsset(isTriaged = true)
    testContext.setAssetCoordinates(positioned.persistedId, latitude = 10, longitude = 20)
    val nowhere = testContext.persistAsset(isTriaged = true)
    val sortedNowhere = testContext.persistAsset()

    withClue("triage: ")(
      subsetMatches(triageView, TriageSubset.NoLocation) shouldBe Set(positioned.persistedId, nowhere.persistedId))
    withClue("browse: ")(subsetMatches(browsingView, TriageSubset.NoLocation) shouldBe Set(sortedNowhere.persistedId))

    val byLocation = searchGrouped(
      new SearchQuery(
        params = triageView,
        subset = Some(TriageSubset.NoLocation),
        rpp = PAGE_SIZE,
        searchSort = byImport,
        grouping = Some(SearchGrouping(GroupBy.Location))))
    byLocation.groups.map(_.key) shouldBe List(SearchGroupKey.Location(None, None, None, None))

    val bounds = testApp.service.library.mapBounds(new SearchQuery(params = triageView, subset = Some(TriageSubset.NoLocation)))
    bounds.map(_.count) shouldBe Some(1)
  }

  /** The IDs a subset matches by the flat page, once the Location-grouped page and both counts agree with it */
  private def subsetMatches(view: Map[String, Any], subset: TriageSubset): Set[String] = {
    val query = new SearchQuery(params = view, subset = Some(subset), rpp = PAGE_SIZE)
    val ids = search(query).records.map(_.persistedId).toSet
    val grouped = searchGrouped(
      new SearchQuery(
        params = view,
        subset = Some(subset),
        rpp = PAGE_SIZE,
        searchSort = byImport,
        grouping = Some(SearchGrouping(GroupBy.Location))))
    withClue("grouped: ")(grouped.assets.map(_.persistedId).toSet shouldBe ids)
    withClue("count: ")(count(query) shouldBe ids.size)
    withClue("capped count: ")(testApp.service.library.cappedCount(query) shouldBe ids.size)
    ids
  }

  /** The triage view's flags, as `SearchRequestParser` sets them (`SearchQuery.TRIAGE_VIEW`) */
  private val triageView = Map[String, Any](FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)

  test("The Triage summary counts the assets in triage, those with Unknown people and those in no Location") {

    /**
     * Setup:
     *
     * Four triaged assets: an unnamed person's Face, that Face in a Location, a named person's Face in that Location, and no
     * Face. A sorted asset and a recycled triaged one, each with the unnamed person's Face.
     *
     * Assertions:
     *
     * The summary counts the four triaged assets, two of them with an Unknown person and two in no Location.
     *
     * Edge cases:
     *
     * The sorted asset and the recycled one are counted nowhere.
     */
    val unknown = testApp.service.person.addPerson(Person())
    val named = addPerson("Alice")

    def triagedWithFacesOf(people: Person*): Asset = {
      val asset = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
      people.foreach(addFace(_, asset))
      asset
    }

    triagedWithFacesOf(unknown)
    val unknownInBeach = triagedWithFacesOf(unknown)
    val namedInBeach = triagedWithFacesOf(named)
    triagedWithFacesOf()
    addFace(unknown, testContext.insertAsset())
    testApp.service.library.recycleAssets(Set(triagedWithFacesOf(unknown).persistedId))
    val beach = testApp.service.location.addLocation("Beach", 1, 2)
    testApp.service.location.addAssets(beach.persistedId, Set(unknownInBeach.persistedId, namedInBeach.persistedId))

    testApp.service.library.triageSummary() shouldBe TriageSummary(total = 4, unknownPeople = 2, noLocation = 2)
  }

  test("Pagination") {

    /**
     * Setup:
     *
     * Six assets, paged by two through cursors, then read in pages of six and twenty.
     *
     * Assertions:
     *
     * Only a first page carries the total, every page but the last carries the next cursor, and the pages hold every asset once.
     *
     * Edge cases:
     *
     * A page size at or above the match count fits everything on the first page; a query without a page size is refused.
     */
    (1 to 6).foreach(n => testContext.persistAsset())

    val results = search(new SearchQuery(rpp = 2))
    results.total shouldBe Some(6)
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true

    val results2 = search(new SearchQuery(rpp = 2, cursor = results.nextCursor))
    results2.total shouldBe None
    results2.records.length shouldBe 2

    val results3 = search(new SearchQuery(rpp = 2, cursor = results2.nextCursor))
    results3.total shouldBe None
    results3.records.length shouldBe 2
    results3.nextCursor shouldBe None

    List(results, results2, results3).flatMap(_.records.map(_.persistedId)).distinct.length shouldBe 6

    val results5 = search(new SearchQuery(rpp = 6))
    results5.total shouldBe Some(6)
    results5.records.length shouldBe 6
    results5.nextCursor shouldBe None

    val results6 = search(new SearchQuery(rpp = 20))
    results6.total shouldBe Some(6)
    results6.records.length shouldBe 6
    results6.nextCursor shouldBe None

    intercept[IllegalArgumentException](search(new SearchQuery()))
  }

  test("An image of more than 2^31 pixels is stored with its whole area and sorts by it, paged through a cursor") {

    /**
     * Setup:
     *
     * Three completed assets written through the DAO: 50,000 × 50,000 pixels (2.5 gigapixels), 46,341 × 46,341 (just over 2^31)
     * and 100 × 100.
     *
     * Assertions:
     *
     * The `area_size` column holds each product whole, and a search sorted by area, largest first, pages through them one at a
     * time in that order.
     *
     * Edge cases:
     *
     * Both large areas are past the 32-bit range, so a product or column of 32 bits would store them negative and sort them last.
     */
    val sizes = List(50_000 -> 50_000, 46_341 -> 46_341, 100 -> 100)
    val ids = testApp.txManager.withTransaction {
      sizes.map {
        (width, height) =>
          val asset = testContext.makeAsset().copy(width = width, height = height)
          testApp.service.asset.markAsCompleted(testApp.DAO.asset.add(asset)).persistedId
      }
    }

    val areas = testApp.txManager.asReadOnly {
      ids.map(id => query("SELECT area_size FROM asset WHERE id = ?", id).head("area_size").asInstanceOf[Number].longValue)
    }
    areas shouldBe sizes.map((width, height) => width.toLong * height)

    val byArea = List(SearchSort(FieldConst.Asset.AREA_SIZE, SortDirection.DESC))
    val pages = Iterator
      .iterate(search(new SearchQuery(rpp = 1, searchSort = byArea)))(
        page => search(new SearchQuery(rpp = 1, searchSort = byArea, cursor = page.nextCursor)))
      .take(ids.length)
      .toList
    pages.flatMap(_.records.map(_.persistedId)) shouldBe ids
    pages.last.nextCursor shouldBe None
  }

  test("A total counts up to its cap and reads one past it when there are more; an exact count does not stop") {

    /**
     * Setup:
     *
     * Four assets, counted under a total cap of 2 and of 4.
     *
     * Assertions:
     *
     * Under a cap below the match count, the flat total, the grouped total and the capped count all read one past the cap. Within
     * the cap the total is exact, and a plain count ignores the cap.
     */
    (1 to 4).foreach(_ => testContext.persistAsset())
    val view = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false)
    val byName = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))

    search(new SearchQuery(params = view, rpp = 3, totalCap = 2)).total shouldBe Some(3)
    testApp.service.library
      .searchGrouped(
        new SearchQuery(
          params = view,
          rpp = 3,
          searchSort = byName,
          grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
          totalCap = 2))
      .total shouldBe Some(3)
    testApp.service.library.cappedCount(new SearchQuery(params = view, totalCap = 2)) shouldBe 3

    testApp.service.library.cappedCount(new SearchQuery(params = view, totalCap = 4)) shouldBe 4
    count(new SearchQuery(params = view, totalCap = 2)) shouldBe 4
  }

  test("A search without a total has none on its first page, and pages as one with a total does") {

    /**
     * Setup:
     *
     * Three assets, read in a flat and a grouped page of two, with a total and without one.
     *
     * Assertions:
     *
     * Without a total the first pages carry none, but hold the same assets and the same cursor as the pages with one.
     */
    (1 to 3).foreach(_ => testContext.persistAsset())
    val view = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false)
    val byName = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))

    val counted = search(new SearchQuery(params = view, rpp = 2, searchSort = byName))
    val uncounted = search(new SearchQuery(params = view, rpp = 2, searchSort = byName).withoutTotal)
    counted.total shouldBe Some(3)
    uncounted.total shouldBe None
    uncounted.records.map(_.persistedId) shouldBe counted.records.map(_.persistedId)
    uncounted.nextCursor shouldBe counted.nextCursor

    val grouped =
      new SearchQuery(
        params = view,
        rpp = 2,
        searchSort = byName,
        grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)))
    val groupedCounted = testApp.service.library.searchGrouped(grouped)
    val groupedUncounted = testApp.service.library.searchGrouped(grouped.withoutTotal)
    groupedCounted.total shouldBe Some(3)
    groupedUncounted.total shouldBe None
    groupedUncounted.assets.map(_.persistedId) shouldBe groupedCounted.assets.map(_.persistedId)
    groupedUncounted.nextCursor shouldBe groupedCounted.nextCursor
  }

  test("Create assets and search by metadata") {

    /**
     * Setup:
     *
     * Two assets with several values in a KEYWORD, a NUMBER and a BOOL field; both hold the number 1 and the flag set.
     *
     * Assertions:
     *
     * Search text finds the one asset with a keyword, and filters on the flag and the number match both.
     *
     * Edge cases:
     *
     * A filter matches a value that is one of several the field holds.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = "field 3", fieldType = FieldType.BOOL))

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("one", "two", "three"),
      field2.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"),
      field3.persistedId -> Set("true"))
    testContext.persistAsset(metadata = UserMetadata(data))

    data = Map[String, Set[String]](
      field1.persistedId -> Set("six", "seven"),
      field2.persistedId -> Set("5", "1001", "1"),
      field3.persistedId -> Set("true"))
    testContext.persistAsset(metadata = UserMetadata(data))

    var results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("one")))
    results.total shouldBe Some(1)

    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(field3.persistedId -> Query.EQUALS(true), field2.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe Some(2)
  }

  test("A BOOL value given in any accepted form is indexed, and a filter on the flag finds it") {

    /**
     * Setup:
     *
     * A BOOL field: an asset imported with "1", one given "TRUE" afterwards, and one given "0" afterwards.
     *
     * Assertions:
     *
     * Each form is indexed as the boolean it stands for, so a filter by true finds the first two and by false the third. A value
     * reads back as it was given.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "flagged", fieldType = FieldType.BOOL))

    val imported: Asset = testContext.persistAsset(metadata = UserMetadata(Map(field.persistedId -> Set("1"))))
    val upper: Asset = testContext.persistAsset()
    testApp.service.metadata.addMetadataValue(upper.persistedId, field.persistedId, "TRUE")
    val zero: Asset = testContext.persistAsset()
    testApp.service.metadata.addMetadataValue(zero.persistedId, field.persistedId, "0")

    def flagged(flag: Boolean): Set[String] =
      search(new SearchQuery(rpp = PAGE_SIZE, metadataFilters = Map(field.persistedId -> Query.EQUALS(flag)))).records
        .map(_.persistedId)
        .toSet

    flagged(true) shouldBe Set(imported.persistedId, upper.persistedId)
    flagged(false) shouldBe Set(zero.persistedId)
    testApp.service.metadata.getMetadata(zero.persistedId)(field.persistedId).map(_.value) shouldBe Set("0")
  }

  test("Search by wrong field type") {

    /**
     * Setup:
     *
     * A single asset with "one" in a KEYWORD field and "1" in a NUMBER field.
     *
     * Assertions:
     *
     * A filter comparing the KEYWORD field to the integer 1 matches nothing rather than failing.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("one"),
      field2.persistedId -> Set("1")
    )
    testContext.persistAsset(metadata = UserMetadata(data))

    val results = search(
      new SearchQuery(rpp = PAGE_SIZE, metadataFilters = Map(field1.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe Some(0)
  }

  test("Parameterized search") {

    /**
     * Setup:
     *
     * Three assets given ("one", 1), ("one", 1) and ("two", 2) in a KEYWORD and a NUMBER field after import.
     *
     * Assertions:
     *
     * Filters on both fields match only the two assets that hold both values.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    val asset3: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.metadata.addMetadataValue(asset2.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.metadata.addMetadataValue(asset3.persistedId, fieldId = field1.persistedId, newValue = "two")

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.metadata.addMetadataValue(asset2.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.metadata.addMetadataValue(asset3.persistedId, fieldId = field2.persistedId, newValue = 2)

    val results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> Query.EQUALS("one"),
          field2.persistedId -> Query.EQUALS(1)
        )
      )
    )
    results.total shouldBe Some(2)
  }

  test("A keyword filter finds its value in another case, canonical form or compatibility form") {

    /**
     * Setup:
     *
     * A KEYWORD field: one asset holds "Straße", "Café" with a combining accent, and "Summer"; another holds "Strand".
     *
     * Assertions:
     *
     * "STRASSE" (case fold), "café" with a precomposed "é" (canonical form) and "ＳUMMER" with a full-width "S" (compatibility
     * form) each find the first asset alone.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()
    List("Straße", "Café", "Summer").foreach {
      value => testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = field.persistedId, newValue = value)
    }
    val other: Asset = testContext.persistAsset()
    testApp.service.metadata.addMetadataValue(other.persistedId, fieldId = field.persistedId, newValue = "Strand")

    List("STRASSE", "café", "ＳUMMER").foreach {
      filter =>
        withClue(s"[$filter]: ") {
          val results = search(new SearchQuery(rpp = PAGE_SIZE, metadataFilters = Map(field.persistedId -> filter)))
          results.records.map(_.persistedId) shouldBe List(asset.persistedId)
        }
    }
  }

  test("Updating and removing metadata values updates search index") {

    /**
     * Setup:
     *
     * An asset with the KEYWORD value "one" and the NUMBER value 3.
     *
     * Assertions:
     *
     * Search text and filters follow the keyword value as it is added and updated, and stop finding the asset once it is removed.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    // The field's only value
    val metadata: UserMetadata = testApp.service.metadata.getMetadata(asset1.persistedId)
    val mdVal = metadata(field1.persistedId).head

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 3)

    var results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("one")))
    results.total shouldBe Some(1)

    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> "one",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe Some(1)

    testApp.service.metadata.updateMetadataValue(asset1.persistedId, mdVal.persistedId, "newone")
    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("newone")))
    results.total shouldBe Some(1)

    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> "newone",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe Some(1)

    testApp.service.metadata.deleteMetadataValue(assetId = asset1.persistedId, valueId = mdVal.persistedId)

    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("newone")))
    results.isEmpty shouldBe true
  }

  test("Can sort in ASC order by created at date") {

    /**
     * Setup:
     *
     * Four assets whose import times are rewritten to one to four hours in the future, an hour apart.
     *
     * Assertions:
     *
     * A search sorted by import time ascending returns them in import order.
     */
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))

    results.records.map(_.persistedId) shouldBe assets.map(_.persistedId)
  }

  test("Can sort in DESC order by created at date") {

    /**
     * Setup:
     *
     * Four assets whose import times are rewritten to one to four hours in the future, an hour apart.
     *
     * Assertions:
     *
     * A search sorted by import time descending returns them newest first.
     */
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.DESC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))

    results.records.map(_.persistedId) shouldBe assets.reverse.map(_.persistedId)
  }

  test("Sort info should be returned with query results") {

    /**
     * Setup:
     *
     * Two assets, searched first with no sort and then with an ascending import-time sort.
     *
     * Assertions:
     *
     * A search with no sort reports none, and a sorted one carries the sort it was read with, its field and its direction.
     */
    (1 to 2).foreach {
      idx =>
        val asset: Asset = testContext.persistAsset()
    }

    search(new SearchQuery(rpp = PAGE_SIZE)).sort shouldBe Nil

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))
    results.sort shouldNot be(empty)
    results.sort.head.direction shouldBe SortDirection.ASC
    results.sort.head.field shouldBe "created_at"
  }

  test("Dangling assets should not be searchable") {

    /**
     * Setup:
     *
     * Three assets marked as not processed by the import pipeline, as an interrupted import leaves them.
     *
     * Assertions:
     *
     * A plain asset query still sees them, but a search finds none.
     */
    val assetCount = 3
    for (_ <- 1 to assetCount)
      testContext.persistAsset()

    val assetQuery = new Query(Map(FieldConst.Asset.FOLDER_ID -> testContext.repository.rootFolderId))
    testApp.service.asset.queryAll(assetQuery).total shouldBe assetCount

    val updateData = Map(
      FieldConst.Asset.IS_PIPELINE_PROCESSED -> false
    )
    testApp.service.asset.updateByQuery(assetQuery, updateData)

    val assetSearchQuery = new SearchQuery(rpp = 3)
    search(assetSearchQuery).total shouldBe Some(0)
  }

  test("An imported asset has a Search document of its file name words") {

    /**
     * Setup:
     *
     * An asset imported as "IMG_1234-beach.final.jpg".
     *
     * Assertions:
     *
     * Its Search document body is the file name's words, and a search for one of them finds it.
     */
    val asset = importAsset("IMG_1234-beach.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach final jpg")
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("beach"))).total shouldBe Some(1)
  }

  test("A Search document holds both readings of a value with a camelCase hump, with and without it") {

    /**
     * Setup:
     *
     * An asset imported as "IMG_1234-beachSunset.final.jpg".
     *
     * Assertions:
     *
     * The document body holds the file name split at the hump, followed by its words with the hump joined.
     */
    val asset = importAsset("IMG_1234-beachSunset.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach sunset final jpg img 1234 beachsunset final jpg")
  }

  test("Search text: a word with a camelCase hump is found by the word in one case, a prefix of it, and its parts") {

    /**
     * Setup:
     *
     * Assets imported as "McDonald_beachSunset.jpg" and, as a decoy, "donut.jpg".
     *
     * Assertions:
     *
     * Only the humped asset is found: by the whole word in any case, a prefix, the part after the hump, and the joined reading.
     */
    val humped = importAsset("McDonald_beachSunset.jpg").persistedId
    importAsset("donut.jpg")

    List("mcdonald", "mcdon", "McDonald", "donald", "sunset", "beachsunset").foreach {
      text => withClue(s"[$text] ")(found(text) shouldBe Set(humped))
    }
  }

  test("Search text: a word typed with a camelCase hump finds the word written in one case") {

    /**
     * Setup:
     *
     * Assets imported as "Mcdonald.jpg", "MCDONALD.jpg" and "Mc Donald.jpg", and a decoy "donald.jpg".
     *
     * Assertions:
     *
     * "McDonald", bare or as a phrase, finds the three spellings but not the decoy.
     *
     * Edge cases:
     *
     * "Mc Donald" is found through the reading split at the typed hump.
     */
    val capitalized = importAsset("Mcdonald.jpg").persistedId
    val upperCase = importAsset("MCDONALD.jpg").persistedId
    val spaced = importAsset("Mc Donald.jpg").persistedId
    importAsset("donald.jpg")

    found("McDonald") shouldBe Set(capitalized, upperCase, spaced)
    found("\"McDonald\"") shouldBe Set(capitalized, upperCase, spaced)
  }

  test("Renaming an asset rewrites its Search document") {

    /**
     * Setup:
     *
     * An asset imported as "beachSunset.jpg", then renamed to "mountain-lake.jpg".
     *
     * Assertions:
     *
     * The document body becomes the new name's words, and the full-text index follows: the old word no longer finds the asset and
     * the new one does.
     */
    val asset = importAsset("beachSunset.jpg")

    testApp.service.asset.rename(asset.persistedId, "mountain-lake.jpg")

    documentBody(asset) shouldBe Some("mountain lake jpg")
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("sunset"))).total shouldBe Some(0)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("lake"))).total shouldBe Some(1)
  }

  test("Editing metadata rewrites the Search document") {

    /**
     * Setup:
     *
     * An asset "beach.jpg" and a KEYWORD field "place" whose value is added, updated and deleted.
     *
     * Assertions:
     *
     * After each edit the document body is the file name's words followed by the current value's, if any.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "place", fieldType = FieldType.KEYWORD))
    val asset = importAsset("beach.jpg")

    testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = field.persistedId, newValue = "Golden-Gate")
    documentBody(asset) shouldBe Some("beach jpg golden gate")

    val value = testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).head
    testApp.service.metadata.updateMetadataValue(asset.persistedId, value.persistedId, "Bay Bridge")
    documentBody(asset) shouldBe Some("beach jpg bay bridge")

    testApp.service.metadata.deleteMetadataValue(assetId = asset.persistedId, valueId = value.persistedId)
    documentBody(asset) shouldBe Some("beach jpg")
  }

  test("A TEXT value added to an asset is found by the next search") {

    /**
     * Setup:
     *
     * Two assets and a TEXT field "notes"; one asset gets the value "The tide comes in" after import.
     *
     * Assertions:
     *
     * The next search finds that asset by a word of the value, and the TEXT value writes no metadata parameter.
     */
    val notes = testApp.service.metadata.addField(UserMetadataField(name = "notes", fieldType = FieldType.TEXT))
    val asset = importAsset("one.jpg")
    importAsset("two.jpg")

    testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = notes.persistedId, newValue = "The tide comes in")

    found("tide") shouldBe Set(asset.persistedId)
    parameterFields(asset) shouldBe List()
  }

  test("An imported or reindexed asset has metadata parameters for its faceted values only, and is found by all of them") {

    /**
     * Setup:
     *
     * An asset imported with values in KEYWORD (two), NUMBER, BOOL, TEXT and DATETIME fields, and an asset without metadata.
     *
     * Assertions:
     *
     * Only the KEYWORD, NUMBER and BOOL values get metadata parameter rows, one per value, yet every value finds the asset: TEXT
     * and DATETIME through Search text, the faceted ones through filters. The same holds after a rename reindexes the asset.
     */
    def addField(name: String, fieldType: FieldType): String =
      testApp.service.metadata.addField(UserMetadataField(name = name, fieldType = fieldType)).persistedId

    val keywords = addField("keywords", FieldType.KEYWORD)
    val count = addField("count", FieldType.NUMBER)
    val isFlagged = addField("flagged", FieldType.BOOL)
    val notes = addField("notes", FieldType.TEXT)
    val shotAt = addField("shot at", FieldType.DATETIME)
    val metadata = Map(
      keywords -> Set("harbor", "boats"),
      count -> Set("3"),
      isFlagged -> Set("true"),
      notes -> Set("The tide comes in"),
      shotAt -> Set("2024-05-01 10:00"))
    val asset = importAsset("one.jpg", metadata = UserMetadata(metadata))
    importAsset("two.jpg")

    def isIndexed(): Unit = {
      parameterFields(asset) shouldBe List(keywords, keywords, count, isFlagged).sorted
      found("tide") shouldBe Set(asset.persistedId)
      found("2024") shouldBe Set(asset.persistedId)
      found("harbor") shouldBe Set(asset.persistedId)
      val filters = Map[String, Any](keywords -> "boats", count -> 3, isFlagged -> true)
      search(new SearchQuery(rpp = PAGE_SIZE, metadataFilters = filters)).records.map(_.persistedId) shouldBe
        List(asset.persistedId)
    }

    isIndexed()
    testApp.service.asset.rename(asset.persistedId, "renamed.jpg")
    isIndexed()
  }

  test("Recycling an asset keeps its Search document") {

    /**
     * Setup:
     *
     * An asset "beach.jpg" moved to the trash.
     *
     * Assertions:
     *
     * Its Search document is still there, unchanged.
     */
    val asset = importAsset("beach.jpg")

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    documentBody(asset) shouldBe Some("beach jpg")
  }

  test("Purging an asset removes its Search document") {

    /**
     * Setup:
     *
     * Two assets, one run through the purge pipeline, and a third imported after the purge.
     *
     * Assertions:
     *
     * The purged asset's document is gone, the other's is untouched, and the purged words find nothing while the others still do.
     *
     * Edge cases:
     *
     * The document written after the purge may take the purged one's place in the table; the full-text index must have forgotten
     * the purged words, or they would lead to it.
     */
    val kept = importAsset("lake.jpg")
    val purged = importAsset("beach.jpg")

    val source = Source.single((purged, PipelineContext(testContext.repository, Some(testContext.user))))
    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)

    documentBody(purged) shouldBe None
    documentBody(kept) shouldBe Some("lake jpg")

    importAsset("mountain.jpg")
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("beach"))).total shouldBe Some(0)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("mountain"))).total shouldBe Some(1)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("lake"))).total shouldBe Some(1)
  }

  test("Rewriting an unchanged Search document updates no row") {

    /**
     * Setup:
     *
     * An imported asset "beach.jpg", reindexed as it is and then with a new file name.
     *
     * Assertions:
     *
     * Reindexing the unchanged asset writes no row of `search_document`, while the new name writes one and updates the body.
     */
    val asset: Asset = testApp.service.asset.getById(importAsset("beach.jpg").persistedId)

    documentRowsWrittenBy(testApp.service.search.reindexAsset(asset)) shouldBe 0
    documentRowsWrittenBy(testApp.service.search.reindexAsset(asset.copy(fileName = "lake.jpg"))) should be > 0L
    documentBody(asset) shouldBe Some("lake jpg")
  }

  test("Folder browsing reads the folder index", Slow) {

    /**
     * Setup:
     *
     * An asset in a folder, seeded into 5,000 copies over 500 folders so the planner has rows to weigh, and a page of 50 of the
     * folder sorted by import time.
     *
     * Assertions:
     *
     * The page's query plan reads the `asset_folder` index.
     */
    val folder: Folder = testApp.service.folder.add("Trips")
    val template = testContext.persistAsset(folder = Some(folder))
    val query = new SearchQuery(params = browsingView, folderIds = Set(folder.persistedId), rpp = 50, searchSort = byImport)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(flatPage(query)))

    withClue(plan)(plan should include("asset_folder"))
  }

  test("A Date Imported page reads the import-time index alone", Slow) {

    /**
     * Setup:
     *
     * Two assets, one seeded into 5,000 copies for the planner, and an unscoped page of 50 sorted by import time, both as a first
     * page and as a continuation from a cursor.
     *
     * Assertions:
     *
     * Both plans read `asset_search_created` alone, not the table (an index-only scan on PostgreSQL, a covering index on SQLite),
     * and the continuation seeks to its cursor's import time rather than reading the pages before it.
     */
    val template = testContext.persistAsset()
    // A second asset, so that a page of one has a cursor
    testContext.persistAsset()
    val first = new SearchQuery(params = browsingView, rpp = 50, searchSort = byImport)
    val cursor = search(new SearchQuery(params = browsingView, rpp = 1, searchSort = byImport)).nextCursor
    val continued = new SearchQuery(params = browsingView, rpp = 50, searchSort = byImport, cursor = cursor)
    // Newest first, so a backward scan on PostgreSQL
    val covering =
      if (isPostgres) "Index Only Scan Backward using asset_search_created" else "USING COVERING INDEX asset_search_created"
    val seek = if (isPostgres) "Index Cond: .*created_at <= " else "asset_search_created \\(.*created_at<\\?\\)"

    val (firstPlan, continuedPlan) = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500)) {
      (indexOnlyPlanOf(flatPage(first), "asset"), indexOnlyPlanOf(flatPage(continued), "asset"))
    }

    withClue(firstPlan)(firstPlan should include(covering))
    withClue(continuedPlan) {
      continuedPlan should include(covering)
      seek.r.findFirstIn(continuedPlan).isDefined shouldBe true
    }
  }

  test("A Date Taken page reads the capture-day index in order", Slow) {

    /**
     * Setup:
     *
     * An asset seeded into 5,000 copies for the planner, and an unscoped, ungrouped page of 50 sorted by capture time, newest
     * first.
     *
     * Assertions:
     *
     * The plan reads `asset_search_date_taken` and does not sort all the matches: PostgreSQL sorts only the index's ties
     * (incrementally), SQLite only the last term of the order.
     */
    val template = testContext.persistAsset()
    val byCapture = List(SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.DESC))
    val query = new SearchQuery(params = browsingView, rpp = 50, searchSort = byCapture)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(flatPage(query)))

    withClue(plan) {
      plan should include("asset_search_date_taken")
      if (isPostgres) plan should include("Incremental Sort")
      else plan should include("USE TEMP B-TREE FOR LAST TERM OF ORDER BY")
    }
  }

  test(
    "A count within a Date Taken range seeks the capture-day index, and within a Date Imported range the import-time index",
    Slow) {

    /**
     * Setup:
     *
     * An asset seeded into 5,000 copies a minute apart over three and a half days, each captured at its import time, and the
     * capped count of the default view within a one-day Date Taken range and a one-day Date Imported range.
     *
     * Assertions:
     *
     * Each count reads one index and not the table, bounded at both ends of the range: `asset_search_date_taken` by capture day,
     * `asset_search_created` by import time.
     */
    val template = persistAssetOfNoon()
    val repositoryId = RequestContext.getRepository.persistedId
    val oneDay = days("2024-06-13", "2024-06-13")
    def countPlan(query: SearchQuery): String =
      indexOnlyPlanOf(SearchQueries.cappedCount(searchDialect, query, repositoryId), "asset")

    val (takenPlan, importedPlan) = atScale(seedCopiesOverDays(template)) {
      (
        countPlan(new SearchQuery(params = browsingView, takenRange = oneDay)),
        countPlan(new SearchQuery(params = browsingView, importedRange = oneDay)))
    }

    def covering(index: String): String =
      if (isPostgres) s"Index Only Scan using $index on asset" else s"USING COVERING INDEX $index "
    val captureDays =
      if (isPostgres) "Index Cond: .*\\(original_created_at\\)::date\\) >= .*\\(original_created_at\\)::date\\) <= "
      else "asset_search_date_taken \\(.*<expr>>\\? AND <expr><\\?\\)"
    val importTimes =
      if (isPostgres) "Index Cond: .*created_at >= .*created_at < "
      else "asset_search_created \\(.*created_at>\\? AND created_at<\\?\\)"

    withClue(takenPlan) {
      takenPlan should include(covering("asset_search_date_taken"))
      captureDays.r.findFirstIn(takenPlan).isDefined shouldBe true
    }
    withClue(importedPlan) {
      importedPlan should include(covering("asset_search_created"))
      importTimes.r.findFirstIn(importedPlan).isDefined shouldBe true
    }
  }

  test("A page within a Date Taken range, sorted by Date Imported, reads the import-time index alone", Slow) {

    /**
     * Setup:
     *
     * An asset seeded over days by `seedCopiesOverDays`, and a page of 50 of the default view sorted by import time, newest
     * first, within a Date Taken range of every day but the newest, so the page begins past the newest imports.
     *
     * Assertions:
     *
     * The plan reads `asset_search_created` in order and tests the capture day on what that index carries, never the table.
     *
     * Edge cases:
     *
     * With the triaged assets added to the view the page reads the same index the same way.
     */
    val template = persistAssetOfNoon()
    val olderDays = days("2024-06-12", "2024-06-14")
    val sorted = new SearchQuery(params = browsingView, rpp = 50, searchSort = byImport, takenRange = olderDays)
    val withTriaged = new SearchQuery(params = withTriagedView, rpp = 50, searchSort = byImport, takenRange = olderDays)

    val plans = atScale(seedCopiesOverDays(template)) {
      List(sorted, withTriaged).map(query => indexOnlyPlanOf(flatPage(query), "asset"))
    }

    // The ordered read of the page slice, newest first, with the range tested on what the index carries
    val orderedRead =
      if (isPostgres)
        "Index Only Scan Backward using asset_search_created on asset [^\\n]*\\n\\s*Index Cond: [^\\n]*\\n\\s*Filter: [^\\n]*" +
          "\\(original_created_at\\)::date >= "
      else
        "MATERIALIZE candidates\\n\\s*SEARCH \\w+ USING COVERING INDEX asset_search_created " +
          "\\(repository_id=\\? AND is_recycled=\\? AND is_pipeline_processed=\\?\\)"

    plans.foreach(plan => withClue(plan)(orderedRead.r.findFirstIn(plan).isDefined shouldBe true))
  }

  test("A page within a Date Imported range, sorted by Date Taken, reads the capture-day index alone", Slow) {

    /**
     * Setup:
     *
     * An asset seeded over days by `seedCopiesOverDays`, and an ungrouped page of 50 of the default view sorted by capture time,
     * newest first, within a Date Imported range of every day but the newest, so the page begins past the newest captures.
     *
     * Assertions:
     *
     * The plan reads `asset_search_date_taken` in order and tests the import time on what that index carries, never the table.
     *
     * Edge cases:
     *
     * With the triaged assets added to the view the page reads the same index the same way.
     */
    val template = persistAssetOfNoon()
    val olderDays = days("2024-06-12", "2024-06-14")
    val byCapture = List(SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.DESC))
    val sorted = new SearchQuery(params = browsingView, rpp = 50, searchSort = byCapture, importedRange = olderDays)
    val withTriaged = new SearchQuery(params = withTriagedView, rpp = 50, searchSort = byCapture, importedRange = olderDays)

    val plans = atScale(seedCopiesOverDays(template)) {
      List(sorted, withTriaged).map(query => indexOnlyPlanOf(flatPage(query), "asset"))
    }

    // The ordered read of the page slice, newest first, with the range tested on what the index carries
    val orderedRead =
      if (isPostgres)
        "Index Only Scan Backward using asset_search_date_taken on asset [^\\n]*\\n\\s*Index Cond: [^\\n]*\\n\\s*Filter: [^\\n]*" +
          "\\(created_at >= "
      else
        "MATERIALIZE candidates\\n\\s*SEARCH \\w+ USING COVERING INDEX asset_search_date_taken " +
          "\\(repository_id=\\? AND is_recycled=\\? AND is_pipeline_processed=\\?\\)"

    plans.foreach(plan => withClue(plan)(orderedRead.r.findFirstIn(plan).isDefined shouldBe true))
  }

  test("People matched all are read through the index that leads with the person and carries the asset", Slow) {

    /**
     * Setup:
     *
     * An asset with Faces of Alice and Bob, seeded into 5,000 copies with Bob's Face on every copy and Alice's on one in ten, and
     * the capped count of the default view matching all of the two.
     *
     * Assertions:
     *
     * The plan reads `face` once per person, each from an index alone: Alice's smaller set from `face_02`, and Bob's from
     * `face_02` too or, on PostgreSQL, by a probe of `face_01` per Alice asset.
     */
    val template = testContext.persistAsset()
    val alice = addPerson("Alice")
    val bob = addPerson("Bob")
    List(alice, bob).foreach(addFace(_, template))
    val query =
      new SearchQuery(params = browsingView, personIds = Set(alice.persistedId, bob.persistedId), peopleMatch = PeopleMatch.All)

    val plan = atScale {
      seedCopies(template.persistedId, copies = 5000, folders = 500)
      // None enrolled, which keeps them out of PostgreSQL's vector index; Alice's goes onto copies whose number ends in 0
      update(
        """INSERT INTO face (id, repository_id, asset_id, person_id, x1, y1, width, height, detection_score, features, checksum,
          |                  quality, is_enrolled)
          |SELECT substr(t.id, 1, 8) || substr(a.id, 9), a.repository_id, a.id, t.person_id, t.x1, t.y1, t.width, t.height,
          |       t.detection_score, t.features, t.checksum, t.quality, FALSE
          |  FROM asset a CROSS JOIN face t
          | WHERE t.asset_id = ? AND a.id <> t.asset_id AND a.repository_id = t.repository_id
          |   AND (t.person_id = ? OR substr(a.id, 36, 1) = '0')""".stripMargin,
        template.persistedId,
        bob.persistedId
      )
    } {
      indexOnlyPlanOf(SearchQueries.cappedCount(searchDialect, query, RequestContext.getRepository.persistedId), "asset", "face")
    }

    val faceReads = plan.linesIterator.filter(_.contains(if (isPostgres) " on face " else " face")).toList
    val indexAlone =
      if (isPostgres) "Index Only Scan using face_0[12] on face " else "SEARCH face\\d+ USING COVERING INDEX face_02 "
    val byPerson =
      if (isPostgres) "(?s)Index Only Scan using face_02 on face .*?Index Cond: \\(person_id = "
      else "face_02 \\(person_id=\\?\\)"

    withClue(plan) {
      faceReads.size shouldBe 2
      faceReads.foreach(read => indexAlone.r.findFirstIn(read).isDefined shouldBe true)
      byPerson.r.findFirstIn(plan).isDefined shouldBe true
    }
  }

  test("The triage view reads the indexes of the triaged assets alone", Slow) {

    /**
     * Setup:
     *
     * A sorted asset seeded into 5,000 copies and three triaged assets: a small triage set in a large library. The triage view's
     * flat page by import time, its capped count, and its page grouped by capture day.
     *
     * Assertions:
     *
     * Each reads a partial index of the triaged assets (`asset_triage_created`, `asset_triage_date_taken`), never the library.
     */
    val template = testContext.persistAsset()
    for (_ <- 1 to 3) testContext.persistAsset(isTriaged = true)
    val triageView = Map[String, Any](FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)
    val flat = new SearchQuery(params = triageView, rpp = 50, searchSort = byImport)
    val grouped = new SearchQuery(
      params = triageView,
      rpp = 50,
      searchSort = List(SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.DESC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    val repositoryId = RequestContext.getRepository.persistedId

    val (pagePlan, countPlan, groupedPlan) = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500)) {
      (
        planOf(flatPage(flat)),
        planOf(SearchQueries.cappedCount(searchDialect, flat, repositoryId)),
        planOf(SearchQueries.grouped(searchDialect, grouped, repositoryId)))
    }

    withClue(pagePlan)(pagePlan should include("asset_triage_created"))
    withClue(countPlan)(countPlan should include("asset_triage_"))
    withClue(groupedPlan)(groupedPlan should include("asset_triage_date_taken"))
  }

  test("An asset's faces, Search document and metadata parameters are found by the index that leads with the asset") {

    /**
     * Setup:
     *
     * One imported asset, and a lookup by its ID in each of `face`, `search_document` and `metadata_parameter`.
     *
     * Assertions:
     *
     * Each plan seeks the table's `_01` index by `asset_id`, not a pass over an index that happens to hold the column.
     *
     * Edge cases:
     *
     * SQLite's `face` may be sought through `face_04` instead, which leads with the asset too and carries the person.
     */
    val engine = searchDialect
    import engine.dialect.*
    val asset = importAsset("beach.jpg")

    for (
      (table, index) <- List(
        "face" -> (if (isPostgres) "face_01" else "face_0[14]"),
        "search_document" -> "search_document_01",
        "metadata_parameter" -> "metadata_parameter_01")
    ) {
      val plan = lookupPlanOf(sql"SELECT asset_id FROM ${SqlStr.raw(table)} WHERE asset_id = ${asset.persistedId}")
      val seek =
        if (isPostgres) s"(?s)$index.*Index Cond: \\(asset_id = "
        else s"SEARCH $table USING (COVERING )?INDEX $index \\(asset_id=\\?"

      withClue(plan)(seek.r.findFirstIn(plan).isDefined shouldBe true)
    }
  }

  test("The people a Search text resolves against are read through a partial index of the live people") {

    /**
     * Setup:
     *
     * The statement that reads the repository's searchable people: named, visible, live, not a bad match.
     *
     * Assertions:
     *
     * Its plan reads `person_01` or `person_03`, the partial indexes of the live people. SQLite matches a partial index's
     * predicate only when the flag is bound as a number, not as text.
     */
    val engine = searchDialect
    val statement = Db.render(SearchQueries.searchablePeople(engine, RequestContext.getRepository.persistedId), engine.dialect)

    val plan = lookupPlanOf(statement.withCompleteQuery(false))

    withClue(plan)("person_0[13]".r.findFirstIn(plan).isDefined shouldBe true)
  }

  test("The live copy of a content is looked up through the index of the live assets", Slow) {

    /**
     * Setup:
     *
     * An asset seeded into 5,000 copies, and a lookup of the repository's live asset by its checksum, with the flag bound as the
     * DAOs bind it.
     *
     * Assertions:
     *
     * The plan seeks `asset_01`, the partial unique index of the live assets, on both engines: the flag, bound as a number,
     * proves the index's predicate.
     */
    val engine = searchDialect
    import engine.dialect.*
    val asset = importAsset("beach.jpg")
    val repositoryId = RequestContext.getRepository.persistedId

    // Over a handful of rows the planner ranks the repository-led indexes by size, and `asset_01`'s 64-character keys lose;
    // at scale the checksum is the selective column
    val plan = atScale(seedCopies(asset.persistedId, copies = 5000, folders = 500)) {
      lookupPlanOf(
        sql"SELECT id FROM asset WHERE repository_id = $repositoryId AND checksum = ${asset.checksum} AND is_recycled = ${false}")
    }

    withClue(plan)(plan should include("asset_01"))
  }

  test("An excluded term is an anti-join on PostgreSQL") {

    /**
     * Setup:
     *
     * Two assets, and the capped count statement of the text "-beach", resolved.
     *
     * Assertions:
     *
     * The plan excludes through an Anti Join (`NOT EXISTS`), not a SubPlan probed per asset as `NOT IN` would be.
     */
    assume(isPostgres)
    importAsset("beach.jpg")
    importAsset("lake.jpg")
    val query = new SearchQuery(text = Some("-beach"), params = browsingView)
    val resolved = query.withResolvedText(testApp.service.search.resolveText(query.textExpression.value))

    val plan = planOf(SearchQueries.cappedCount(searchDialect, resolved, RequestContext.getRepository.persistedId))

    withClue(plan)(plan should include("Anti Join"))
  }

  test("Text with a positive group that has no hit matches nothing, and an exclusion alone is matched over the library") {

    /**
     * Setup:
     *
     * One asset, "beach.jpg".
     *
     * Assertions:
     *
     * A positive group with no hit ("zzz OR qqq") resolves to no candidates, so the text matches nothing. Text that is only an
     * exclusion has no hits to read and is matched over the whole library.
     */
    val beach = importAsset("beach.jpg").persistedId

    testApp.service.search.resolveText(SearchText.parse("zzz OR qqq beach").value).candidates shouldBe Some(Set())
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("zzz OR qqq beach"))).total shouldBe Some(0)
    found("zzz") shouldBe Set()

    testApp.service.search.resolveText(SearchText.parse("-zzz").value).candidates shouldBe None
    found("-zzz") shouldBe Set(beach)
  }

  test("A selective search reads the assets by their primary key", Slow) {

    /**
     * Setup:
     *
     * An asset "beach.jpg" seeded into 5,000 copies without a Search document, so the text "beach" has one candidate.
     *
     * Assertions:
     *
     * The capped count's plan reads the asset by a key that leads with its ID: the primary key, or the (ID, repository) unique
     * key the foreign keys name.
     */
    val template = importAsset("beach.jpg")
    val query = new SearchQuery(text = Some("beach"), params = browsingView)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500)) {
      val resolved = query.withResolvedText(testApp.service.search.resolveText(query.textExpression.value))
      resolved.requireResolvedText.candidates shouldBe Some(Set(template.persistedId))
      planOf(SearchQueries.cappedCount(searchDialect, resolved, RequestContext.getRepository.persistedId))
    }

    withClue(plan)(
      (plan should include).regex(if (isPostgres) "asset_pkey|asset_id_repository_id_key" else "sqlite_autoindex_asset_[12]"))
  }

  test("The name sources of a Search text are read from their indexes alone", Slow) {

    /**
     * Setup:
     *
     * Two assets, both with a Face of Alice, in the Location Rome and in the album Trip. "beach alice rome trip" is resolved with
     * a probe limit of one, so "beach" yields the one candidate and the other terms are tested on its row.
     *
     * Assertions:
     *
     * The probe reads each name source from the index that leads with it, and the search tests the candidate's memberships and
     * faces through an index that leads with the asset, never reading a table: `face_01`, which carries the person on PostgreSQL,
     * and on SQLite, which has no included columns, `face_04`.
     */
    val alice = addPerson("Alice")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, None)
    val trip = testApp.service.album.add("Trip")
    val assets = List(importAsset("beach.jpg"), importAsset("lake.jpg"))
    assets.foreach(addFace(alice, _))
    testApp.service.location.addAssets(rome.persistedId, assets.map(_.persistedId).toSet)
    testApp.service.album.addAssets(trip.persistedId, assets.map(_.persistedId).toSet)
    val query = new SearchQuery(text = Some("beach alice rome trip"), params = browsingView)
    val repositoryId = RequestContext.getRepository.persistedId
    val tables = List("face", "location_asset", "album_asset")

    def plans: (String, String) = {
      val text = testApp.service.search.resolveText(query.textExpression.value, probeLimit = 1)
      text.candidates shouldBe Some(Set(assets.head.persistedId))
      (
        indexOnlyPlanOf(SearchQueries.textProbe(searchDialect, text, repositoryId, limit = 1).value, tables*),
        indexOnlyPlanOf(SearchQueries.cappedCount(searchDialect, query.withResolvedText(text), repositoryId), tables*))
    }
    // PostgreSQL needs analyzed tables to be told they are vacuumed. SQLite, told they hold two rows, would scan rather than
    // seek; without statistics it plans as it does for a library.
    val (probePlan, searchPlan) = if (isPostgres) atScale(())(plans) else plans

    // Over two rows PostgreSQL may read either index of a membership table; what matters is that it reads no table
    def covering(table: String, index: String): String =
      if (isPostgres) s"Index Only Scan using \\w+ on $table " else s"USING COVERING INDEX $index "
    def reads(plan: String, table: String, index: String): Unit =
      withClue(s"$table in $plan: ")(covering(table, index).r.findFirstIn(plan).isDefined shouldBe true)

    reads(probePlan, "face", "face_02")
    reads(probePlan, "location_asset", "location_asset_01")
    reads(probePlan, "album_asset", "album_asset_01")
    reads(searchPlan, "location_asset", "location_asset_02")
    reads(searchPlan, "album_asset", "album_asset_02")
    reads(searchPlan, "face", if (isPostgres) "face_01" else "face_04")
  }

  /** Three assets whose file names and "place" metadata values share words in different orders */
  private def fixtureForText: Object { val beach: String; val lake: String; val dog: String } = new {
    private val place = testApp.service.metadata.addField(UserMetadataField(name = "place", fieldType = FieldType.KEYWORD))

    private def add(fileName: String, placeValue: String): String =
      val asset = importAsset(fileName)
      testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = place.persistedId, newValue = placeValue)
      asset.persistedId

    val beach: String = add("IMG_1234-beachSunset.jpg", "Golden Gate")
    val lake: String = add("IMG_1299-mountainLake.jpg", "Bay Bridge")
    val dog: String = add("sunsetBeach.png", "Golden Retriever")
  }

  test("Search text: every term must match, in the file name or in a metadata value") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * An asset is found only when every term matches it, each in either its file name or its place value.
     */
    val f = fixtureForText

    found("beach golden") shouldBe Set(f.beach, f.dog)
    found("gate beach") shouldBe Set(f.beach)
    found("beach bridge") shouldBe Set()
  }

  test("Search text: a term matches the start of a word") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * A term matches the words it is a prefix of, in any case, and not those it is only inside of.
     */
    val f = fixtureForText

    found("sun") shouldBe Set(f.beach, f.dog)
    found("GOLD") shouldBe Set(f.beach, f.dog)
    found("unset") shouldBe Set()
  }

  test("Search text: a term of several words is those words in sequence, the last one a prefix") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * A term that splits into several words matches them only consecutively and in order, all whole but the last, which may be a
     * prefix.
     */
    val f = fixtureForText

    found("IMG_12") shouldBe Set(f.beach, f.lake)
    found("img-123") shouldBe Set(f.beach)
    found("beachSun") shouldBe Set(f.beach)
    found("12_IMG") shouldBe Set()
    found("IM_1234") shouldBe Set()
  }

  test("Search text: a phrase is whole consecutive words") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * A phrase matches its words whole, consecutively and in the order typed, in the file name or in a metadata value.
     *
     * Edge cases:
     *
     * An unbalanced quote is closed at the end of the text.
     */
    val f = fixtureForText

    found("\"beach sunset\"") shouldBe Set(f.beach)
    found("\"sunset beach\"") shouldBe Set(f.dog)
    found("\"Golden Gate\"") shouldBe Set(f.beach)
    found("\"beach sun\"") shouldBe Set()
    found("\"gold\"") shouldBe Set()
    found("\"golden retriever") shouldBe Set(f.dog)
  }

  test("Search text: OR makes alternatives and binds tighter than AND") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * Alternatives joined by OR match either term, and the group is AND-ed with the terms around it.
     *
     * Edge cases:
     *
     * A phrase can be an alternative; a lower-case "or" is an ordinary word.
     */
    val f = fixtureForText

    found("gate OR bridge") shouldBe Set(f.beach, f.lake)
    found("retriever OR bridge img") shouldBe Set(f.lake)
    found("img \"bay bridge\" OR 1234") shouldBe Set(f.beach, f.lake)
    found("gate or bridge") shouldBe Set()
  }

  test("Search text: a leading minus excludes") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * An excluded term, phrase or multi-word term removes the assets it matches, alone or beside positive terms.
     *
     * Edge cases:
     *
     * An excluded term can be an alternative: "-golden OR gate" is not golden, or else a gate.
     */
    val f = fixtureForText

    found("golden -gate") shouldBe Set(f.dog)
    found("-golden") shouldBe Set(f.lake)
    found("-gold -lake") shouldBe Set()
    found("beach -\"beach sunset\"") shouldBe Set(f.dog)
    found("-IMG_12") shouldBe Set(f.dog)
    found("-golden OR gate") shouldBe Set(f.beach, f.lake)
  }

  test("Search text: a sigma at a word's end matches in either form, typed in either case") {

    /**
     * Setup:
     *
     * Assets named "ΟΔΟΣ.jpg" (capitals), "οδος.jpg" (a final sigma, ς), "οδοσ.jpg" (a medial sigma at the end, σ) and
     * "ΟΔΟΣΗΜΑΝΣΗ.jpg", a word that starts with the first, and a folder "Οδός" holding one more asset.
     *
     * Assertions:
     *
     * The word typed in capitals, with a final sigma or with a medial one finds the same assets on both engines: the three
     * spellings, the longer word by its start, and the folder's asset by the folder's name.
     *
     * Edge cases:
     *
     * Lower-casing a capital sigma at a word's end gives the final form, which a document, a name and the typed text must not
     * tell from the medial one.
     */
    val folder: Folder = testApp.service.folder.add("Οδός")
    val spellings = List("ΟΔΟΣ.jpg", "οδος.jpg", "οδοσ.jpg").map(importAsset(_).persistedId)
    val longer = importAsset("ΟΔΟΣΗΜΑΝΣΗ.jpg").persistedId
    val inFolder = importAsset("one.jpg", Some(folder)).persistedId
    importAsset("two.jpg")

    List("ΟΔΟΣ", "οδος", "οδοσ").foreach {
      typed => withClue(s"$typed: ")(found(typed) shouldBe (spellings :+ longer :+ inFolder).toSet)
    }
  }

  test("Search text: a letter with two lower-case forms matches in either") {

    /**
     * Setup:
     *
     * Assets named "µm.jpg" (the micro sign) and "μm.jpg" (the Greek small mu), and a folder "ϕως" (the Greek phi symbol) holding
     * an asset named "one.jpg", beside an asset named "two.jpg".
     *
     * Assertions:
     *
     * The word typed with either mu finds both assets on both engines, and the folder's name typed with the plain phi, or in
     * capitals, finds its asset.
     */
    val folder: Folder = testApp.service.folder.add("ϕως")
    val mus = List("µm.jpg", "μm.jpg").map(importAsset(_).persistedId).toSet
    val inFolder = importAsset("one.jpg", Some(folder)).persistedId
    importAsset("two.jpg")

    List("µm", "μm", "ΜM").foreach(typed => withClue(s"$typed: ")(found(typed) shouldBe mus))
    List("φως", "ΦΩΣ", "ϕως").foreach(typed => withClue(s"$typed: ")(found(typed) shouldBe Set(inFolder)))
  }

  test("Search text: nothing typed is query syntax, and text without a word is no text") {

    /**
     * Setup:
     *
     * The three assets of `fixtureForText`.
     *
     * Assertions:
     *
     * The engines' full-text operators (`&`, `|`, `!`, `:*`, quotes, parentheses, AND, NOT, NEAR) are matched as plain words or
     * separators, and text with no usable word matches every asset, like no text at all.
     */
    val f = fixtureForText

    found("beach & (sunset:* | 'x') !") shouldBe Set()
    found("beach* AND NOT NEAR(lake)") shouldBe Set()
    found("(beach) & sunset:*") shouldBe Set(f.beach, f.dog)
    found("- !!! OR") shouldBe Set(f.beach, f.lake, f.dog)
  }

  test("Search names: a named, visible person finds the assets with a Face of theirs") {

    /**
     * Setup:
     *
     * One asset with a Face of "Alice Liddell", another with Faces of a hidden, a bad-match and an unnamed person.
     *
     * Assertions:
     *
     * A name finds the assets with that person's Face, by a word, a prefix, or two words of it; hidden, bad-match and unnamed
     * people find nothing.
     *
     * Edge cases:
     *
     * An unnamed person's generated "Unknown N" name is not searchable.
     */
    val withAlice = importAsset("one.jpg")
    val withOthers = importAsset("two.jpg")
    addFace(addPerson("Alice Liddell"), withAlice)

    val hidden = addPerson("Harry Hidden")
    val badMatch = addPerson("Bob Badmatch")
    val unnamed = testApp.service.person.addPerson(Person())
    List(hidden, badMatch, unnamed).foreach(addFace(_, withOthers))
    testApp.service.person.setVisibility(hidden, isHidden = true)
    testApp.service.person.markAsBadMatch(badMatch)

    found("alice") shouldBe Set(withAlice.persistedId)
    found("lidd") shouldBe Set(withAlice.persistedId)
    found("liddell alice") shouldBe Set(withAlice.persistedId)
    found("harry") shouldBe Set()
    found("bob") shouldBe Set()
    unnamed.name.value.startsWith(PersonService.UNKNOWN_NAME_PREFIX) shouldBe true
    found(PersonService.UNKNOWN_NAME_PREFIX) shouldBe Set()
  }

  test("Search names: renaming, hiding and merging people shows in the next search") {

    /**
     * Setup:
     *
     * Two assets with a Face each, of Alice and of Bob.
     *
     * Assertions:
     *
     * The next search follows each change: a renamed person is found by the new name only, a hidden one not at all until shown
     * again, and a merge leaves both assets under the surviving name.
     *
     * Edge cases:
     *
     * The person merged away is no candidate at all, whatever Faces it may still have.
     */
    val withAlice = importAsset("one.jpg")
    val withBob = importAsset("two.jpg")
    val alice = addPerson("Alice")
    val bob = addPerson("Bob")
    addFace(alice, withAlice)
    addFace(bob, withBob)

    testApp.service.person.updateName(alice, "Alicia")
    found("alice") shouldBe Set()
    found("alicia") shouldBe Set(withAlice.persistedId)

    testApp.service.person.setVisibility(bob, isHidden = true)
    found("bob") shouldBe Set()
    testApp.service.person.setVisibility(bob, isHidden = false)
    found("bob") shouldBe Set(withBob.persistedId)

    testApp.service.person.merge(dest = testApp.service.person.getById(alice.persistedId), source = bob)
    found("alicia") shouldBe Set(withAlice.persistedId, withBob.persistedId)
    found("bob") shouldBe Set()
    personHits("bob") shouldBe Set()
    personHits("alicia") shouldBe Set(alice.persistedId)
  }

  test("Search names: a Location finds its assets, a Category those of its Locations") {

    /**
     * Setup:
     *
     * Rome and Milan in the Category "Italy", Paris without a Category, one asset in each, and a fourth asset in none.
     *
     * Assertions:
     *
     * A Location's name finds its assets and a Category's name those of all its Locations, with prefixes, exclusions and OR like
     * any term.
     */
    val italy = testApp.service.location.addCategory("Italy")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, Some(italy.persistedId))
    val milan = testApp.service.location.addLocation("Milan", 45.5, 9.2, Some(italy.persistedId))
    val paris = testApp.service.location.addLocation("Paris", 48.9, 2.4)
    val inRome = importAsset("one.jpg")
    val inMilan = importAsset("two.jpg")
    val inParis = importAsset("three.jpg")
    importAsset("four.jpg")
    testApp.service.location.addAssets(rome.persistedId, Set(inRome.persistedId))
    testApp.service.location.addAssets(milan.persistedId, Set(inMilan.persistedId))
    testApp.service.location.addAssets(paris.persistedId, Set(inParis.persistedId))

    found("rome") shouldBe Set(inRome.persistedId)
    found("italy") shouldBe Set(inRome.persistedId, inMilan.persistedId)
    found("par") shouldBe Set(inParis.persistedId)
    found("italy -milan") shouldBe Set(inRome.persistedId)
    found("rome OR paris") shouldBe Set(inRome.persistedId, inParis.persistedId)
  }

  test("Search names: renaming, moving, deleting and refilling Locations shows in the next search") {

    /**
     * Setup:
     *
     * Rome in the Category "Italy" holding one asset, an empty Category "France", and a second asset.
     *
     * Assertions:
     *
     * The next search follows each rename, move, membership change and deletion of the Location and its Category.
     *
     * Edge cases:
     *
     * A deleted Category leaves its Locations at the top level, still found by their own names.
     */
    val italy = testApp.service.location.addCategory("Italy")
    val france = testApp.service.location.addCategory("France")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, Some(italy.persistedId))
    val asset = importAsset("one.jpg").persistedId
    val other = importAsset("two.jpg").persistedId
    testApp.service.location.addAssets(rome.persistedId, Set(asset))

    testApp.service.location.rename(rome.persistedId, "Roma")
    found("rome") shouldBe Set()
    found("roma") shouldBe Set(asset)

    testApp.service.location.rename(italy.persistedId, "Italia")
    found("italy") shouldBe Set()
    found("italia") shouldBe Set(asset)

    testApp.service.location.moveToCategory(rome.persistedId, Some(france.persistedId))
    found("italia") shouldBe Set()
    found("france") shouldBe Set(asset)

    testApp.service.location.addAssets(rome.persistedId, Set(other))
    found("roma") shouldBe Set(asset, other)
    testApp.service.location.removeAssets(rome.persistedId, Set(asset))
    found("france") shouldBe Set(other)

    testApp.service.location.deleteById(france.persistedId)
    found("france") shouldBe Set()
    found("roma") shouldBe Set(other)

    testApp.service.location.deleteById(rome.persistedId)
    found("roma") shouldBe Set()
  }

  test("Search names: a folder finds the assets in it and in every folder below it, the root none") {

    /**
     * Setup:
     *
     * Folders Trips > Japan 2019 > Kyoto with an asset at each level, a folder "Work" with an asset, and an asset in the root.
     *
     * Assertions:
     *
     * A folder's name, a digit word of it included, finds the assets in it and below it, and terms may match different folders of
     * one path, exclusions included.
     *
     * Edge cases:
     *
     * The root folder holds everything and its name finds nothing.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val japan: Folder = testApp.service.folder.add("Japan 2019", Some(trips.persistedId))
    val kyoto: Folder = testApp.service.folder.add("Kyoto", Some(japan.persistedId))
    val work: Folder = testApp.service.folder.add("Work")
    val inTrips = importAsset("one.jpg", Some(trips)).persistedId
    val inJapan = importAsset("two.jpg", Some(japan)).persistedId
    val inKyoto = importAsset("three.jpg", Some(kyoto)).persistedId
    importAsset("four.jpg", Some(work))
    importAsset("five.jpg")

    found("trips") shouldBe Set(inTrips, inJapan, inKyoto)
    found("japan") shouldBe Set(inJapan, inKyoto)
    found("2019") shouldBe Set(inJapan, inKyoto)
    found("kyoto") shouldBe Set(inKyoto)
    found("trips kyoto") shouldBe Set(inKyoto)
    found("trips -japan") shouldBe Set(inTrips)
    found(FieldConst.Folder.Name.ROOT) shouldBe Set()
  }

  test("Search names: renaming, moving and deleting folders, and moving assets, shows in the next search") {

    /**
     * Setup:
     *
     * Folders Trips > Japan with one asset, a folder "Archive", and a second asset in the root folder.
     *
     * Assertions:
     *
     * The next search follows the folder paths through each rename, folder move, asset move, deletion and restore.
     *
     * Edge cases:
     *
     * A deleted folder is recycled with everything below it, and none of them names anything, even for a search that includes the
     * trash; restoring an asset restores its folders and their names.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val japan: Folder = testApp.service.folder.add("Japan", Some(trips.persistedId))
    val archive: Folder = testApp.service.folder.add("Archive")
    val asset = importAsset("one.jpg", Some(japan)).persistedId
    val other = importAsset("two.jpg").persistedId

    testApp.service.folder.rename(japan.persistedId, "Nippon")
    found("japan") shouldBe Set()
    found("nippon") shouldBe Set(asset)

    testApp.service.folder.move(japan.persistedId, archive.persistedId)
    found("trips") shouldBe Set()
    found("archive") shouldBe Set(asset)

    testApp.service.library.moveAssetsToFolder(Set(other), japan.persistedId)
    found("nippon") shouldBe Set(asset, other)
    testApp.service.library.moveAssetsToFolder(Set(asset), trips.persistedId)
    found("archive") shouldBe Set(other)
    found("trips") shouldBe Set(asset)

    testApp.service.library.deleteFolderById(archive.persistedId)
    found("archive") shouldBe Set()
    found("nippon") shouldBe Set()
    found("trips") shouldBe Set(asset)

    testApp.service.library.restoreRecycledAssets(Set(other))
    found("archive") shouldBe Set(other)
  }

  test("Search names: an album finds its assets, and its changes show in the next search") {

    /**
     * Setup:
     *
     * An album "Best of Summer" holding one of two assets.
     *
     * Assertions:
     *
     * The album's name finds its assets by a word or as a phrase, and the next search follows each rename, membership change and
     * deletion.
     */
    val album = testApp.service.album.add("Best of Summer")
    val asset = importAsset("one.jpg").persistedId
    val other = importAsset("two.jpg").persistedId
    testApp.service.album.addAssets(album.persistedId, Set(asset))

    found("summer") shouldBe Set(asset)
    found("\"best of summer\"") shouldBe Set(asset)

    testApp.service.album.rename(album.persistedId, "Winter")
    found("summer") shouldBe Set()
    found("winter") shouldBe Set(asset)

    testApp.service.album.addAssets(album.persistedId, Set(other))
    found("winter") shouldBe Set(asset, other)
    testApp.service.album.removeAssets(album.persistedId, Set(asset))
    found("winter") shouldBe Set(other)

    testApp.service.album.deleteById(album.persistedId)
    found("winter") shouldBe Set()
  }

  test("Search names: a phrase, and a term of several words, match within one name") {

    /**
     * Setup:
     *
     * An asset "ann.jpg" with a Face of "Mary", in the album "Mary" and the Location "Ann Arbor", and another asset in the folder
     * "Mary Ann".
     *
     * Assertions:
     *
     * Separate terms may match in different names and in the Search document, but a phrase or a term of several words must match
     * within one name or within the document.
     *
     * Edge cases:
     *
     * A phrase never crosses two names, and its last word must be whole.
     */
    val annArbor = testApp.service.location.addLocation("Ann Arbor", 42.3, -83.7)
    val album = testApp.service.album.add("Mary")
    val folder: Folder = testApp.service.folder.add("Mary Ann")
    val split = importAsset("ann.jpg").persistedId
    val whole = importAsset("one.jpg", Some(folder)).persistedId
    addFace(addPerson("Mary"), testApp.service.asset.getById(split))
    testApp.service.album.addAssets(album.persistedId, Set(split))
    testApp.service.location.addAssets(annArbor.persistedId, Set(split))

    // Both words are on the first asset, but in different names and in the document
    found("mary ann") shouldBe Set(split, whole)
    found("\"mary ann\"") shouldBe Set(whole)
    found("mary-an") shouldBe Set(whole)
    found("\"ann arbor\"") shouldBe Set(split)
    found("\"ann jpg\"") shouldBe Set(split)
    found("\"mary ann arbor\"") shouldBe Set()
    found("\"mary an\"") shouldBe Set()
  }

  test("Search names: a name with a camelCase hump is found by the word in one case and by the part after the hump") {

    /**
     * Setup:
     *
     * One asset reached by a humped name of each kind (person, folder, album, Location, Category), one with a Face of "Leblanc",
     * and a decoy.
     *
     * Assertions:
     *
     * Every kind of humped name is found by the whole word in one case and by the part after the hump.
     *
     * Edge cases:
     *
     * The other way round: "Leblanc" is found by "LeBlanc".
     */
    val byPerson = importAsset("one.jpg")
    val byFolder = importAsset("two.jpg", Some(testApp.service.folder.add("MacArthur"))).persistedId
    val byAlbum = importAsset("three.jpg").persistedId
    val byLocation = importAsset("four.jpg").persistedId
    val byCategory = importAsset("five.jpg").persistedId
    val byOneCaseName = importAsset("six.jpg")
    importAsset("seven.jpg")

    addFace(addPerson("DeShawn"), byPerson)
    testApp.service.album.addAssets(testApp.service.album.add("LaGuardia").persistedId, Set(byAlbum))
    testApp.service.location.addAssets(testApp.service.location.addLocation("McAllen", 26.2, -98.2).persistedId, Set(byLocation))
    val dekalb = testApp.service.location.addCategory("DeKalb")
    val sycamore = testApp.service.location.addLocation("Sycamore", 41.9, -88.7, Some(dekalb.persistedId))
    testApp.service.location.addAssets(sycamore.persistedId, Set(byCategory))
    addFace(addPerson("Leblanc"), byOneCaseName)

    found("deshawn") shouldBe Set(byPerson.persistedId)
    found("shawn") shouldBe Set(byPerson.persistedId)
    found("macarthur") shouldBe Set(byFolder)
    found("arthur") shouldBe Set(byFolder)
    found("laguardia") shouldBe Set(byAlbum)
    found("guardia") shouldBe Set(byAlbum)
    found("mcallen") shouldBe Set(byLocation)
    found("allen") shouldBe Set(byLocation)
    found("dekalb") shouldBe Set(byCategory)
    found("kalb") shouldBe Set(byCategory)
    found("LeBlanc") shouldBe Set(byOneCaseName.persistedId)
  }

  test("Search names: a phrase matches within one reading of a name with a camelCase hump") {

    /**
     * Setup:
     *
     * An asset in the Location "LaGuardia Airport".
     *
     * Assertions:
     *
     * A phrase matches the name typed in one case, split at the hump, or with the hump.
     *
     * Edge cases:
     *
     * The two readings are not one run of words, so a phrase cannot run from the end of one into the other.
     */
    val airport = testApp.service.location.addLocation("LaGuardia Airport", 40.8, -73.9)
    val asset = importAsset("one.jpg").persistedId
    testApp.service.location.addAssets(airport.persistedId, Set(asset))

    found("\"laguardia airport\"") shouldBe Set(asset)
    found("\"la guardia airport\"") shouldBe Set(asset)
    found("\"LaGuardia airport\"") shouldBe Set(asset)
    found("\"airport laguardia\"") shouldBe Set()
  }

  test("Search names: terms combine across sources, and an exclusion holds in every source") {

    /**
     * Setup:
     *
     * Alice's Face in three assets (in the Location Rome, in the folder "Rome trip", in neither), and an asset named "rome.jpg".
     *
     * Assertions:
     *
     * Terms may each match in a different source, and an excluded term removes an asset when it matches in any source.
     */
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5)
    val folder: Folder = testApp.service.folder.add("Rome trip")
    val alice = addPerson("Alice")
    val aliceInRome = importAsset("one.jpg")
    val aliceInFolder = importAsset("two.jpg", Some(folder))
    val aliceAlone = importAsset("three.jpg")
    val romeFile = importAsset("rome.jpg").persistedId
    List(aliceInRome, aliceInFolder, aliceAlone).foreach(addFace(alice, _))
    testApp.service.location.addAssets(rome.persistedId, Set(aliceInRome.persistedId))

    found("alice rome") shouldBe Set(aliceInRome.persistedId, aliceInFolder.persistedId)
    found("rome") shouldBe Set(aliceInRome.persistedId, aliceInFolder.persistedId, romeFile)
    found("alice -rome") shouldBe Set(aliceAlone.persistedId)
    found("-alice") shouldBe Set(romeFile)
    found("-alice OR three") shouldBe Set(romeFile, aliceAlone.persistedId)
  }

  test("Search names: an excluded folder name keeps the assets in no folder") {

    /**
     * Setup:
     *
     * A folder "Rome trip" with a sorted asset in it, and a triaged asset, which is in no folder, with coordinates and no "rome"
     * in its document.
     *
     * Assertions:
     *
     * In the Triage view, "-rome", "-rome OR nothing" and "three -rome" find the triaged asset, and the count, the grouped page
     * and the map agree. The first two have no positive group and read the broad path whatever the probe limit; "three" is
     * probed, so that text reads both paths, the selective one testing the exclusion on the asset's own row.
     *
     * Edge cases:
     *
     * In the repository view "-rome" still leaves out the asset in the folder.
     */
    val folder: Folder = testApp.service.folder.add("Rome trip")
    importAsset("one.jpg", Some(folder))
    val elsewhere = importAsset("two.jpg").persistedId
    val triaged = testContext.insertAsset(testContext.makeAsset(filename = "three.jpg", isTriaged = true)).persistedId
    testContext.setAssetCoordinates(triaged, latitude = 41.9, longitude = 12.5)

    List("-rome", "-rome OR nothing", "three -rome").foreach {
      text =>
        withClue(s"$text: ") {
          val query = new SearchQuery(params = triageView, text = Some(text), rpp = PAGE_SIZE)
          search(query).records.map(_.persistedId) shouldBe List(triaged)
          count(query) shouldBe 1
          val grouped = searchGrouped(
            new SearchQuery(
              params = triageView,
              text = Some(text),
              rpp = PAGE_SIZE,
              searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
              grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
            ))
          grouped.assets.map(_.persistedId) shouldBe List(triaged)
          onBothPaths(query)(testApp.service.library.mapBounds)(_.map(_.count)).map(_.count) shouldBe Some(1)
          onBothPaths(query)(testApp.service.library.mapCells(_, BoundingBox(-90, -180, 90, 180), zoom = 0))(
            _.cells.map(_.count).sum).cells.map(_.count).sum shouldBe 1
        }
    }

    search(new SearchQuery(params = browsingView, text = Some("-rome"), rpp = PAGE_SIZE)).records.map(_.persistedId) shouldBe
      List(elsewhere)
  }

  test("Search names: counts and grouped pages follow the names, resolved afresh for every page") {

    /**
     * Setup:
     *
     * Two assets in the folder "Trips" and one outside it, searched for "trips" in grouped pages of one asset.
     *
     * Assertions:
     *
     * The count and the first page's total follow the folder name, and the next page finds an asset moved meanwhile into a new
     * subfolder: the cursor keeps the text as typed, and the names are resolved again for every page.
     */
    val folder: Folder = testApp.service.folder.add("Trips")
    val first = importAsset("a.jpg", Some(folder)).persistedId
    val second = importAsset("b.jpg", Some(folder)).persistedId
    importAsset("c.jpg")

    def page(cursor: Option[SearchCursor]): GroupedSearchResult =
      searchGrouped(
        new SearchQuery(
          text = Some("trips"),
          rpp = 1,
          searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
          grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
          cursor = cursor
        ))

    count(new SearchQuery(text = Some("trips"))) shouldBe 2

    val firstPage = page(None)
    firstPage.total shouldBe Some(2)
    firstPage.assets.map(_.persistedId) shouldBe List(first)

    val sub: Folder = testApp.service.folder.add("Sub", Some(folder.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(second), sub.persistedId)
    page(firstPage.nextCursor).assets.map(_.persistedId) shouldBe List(second)
  }

  test("Relevance: an asset ranks by the best source the term matched it in") {

    /**
     * Setup:
     *
     * The term "al" reaches one asset through each source, one asset through all of them, and misses one. Capture times order the
     * assets of equal worth.
     *
     * Assertions:
     *
     * Assets rank by their best source: a person, a Location, a Category, then a folder or an album alike, then the file name,
     * the newer capture first among equals.
     *
     * Edge cases:
     *
     * The asset matched in every source is worth its best source, not the sum of them.
     */
    val folder: Folder = testApp.service.folder.add("Alfa")
    val alps = testApp.service.location.addCategory("Alps")
    val chamonix = testApp.service.location.addLocation("Chamonix", 45.9, 6.9, Some(alps.persistedId))
    val alba = testApp.service.location.addLocation("Alba", 44.7, 8.0)
    val album = testApp.service.album.add("Alto")
    val alice = addPerson("Alice")

    val byDocument = importAsset("almond.jpg")
    val byAlbum = importAsset("one.jpg")
    val byFolder = importAsset("two.jpg", Some(folder))
    val byCategory = importAsset("three.jpg")
    val byLocation = importAsset("four.jpg")
    val byPerson = importAsset("five.jpg")
    val byEverySource = importAsset("alder.jpg", Some(folder))
    importAsset("unrelated.jpg")

    testApp.service.album.addAssets(album.persistedId, Set(byAlbum.persistedId, byEverySource.persistedId))
    testApp.service.location.addAssets(chamonix.persistedId, Set(byCategory.persistedId, byEverySource.persistedId))
    testApp.service.location.addAssets(alba.persistedId, Set(byLocation.persistedId, byEverySource.persistedId))
    List(byPerson, byEverySource).foreach(addFace(alice, _))

    // Ties: the folder and the album, and the two assets of the person
    takenAt(byFolder, "2026-09-06T10:00:00")
    takenAt(byAlbum, "2026-09-05T10:00:00")
    takenAt(byPerson, "2026-09-06T10:00:00")
    takenAt(byEverySource, "2026-09-05T10:00:00")

    ranked("al") shouldBe
      List(byPerson, byEverySource, byLocation, byCategory, byFolder, byAlbum, byDocument).map(_.persistedId)
  }

  test("Relevance: terms add up, alternatives count as the best of them, and an exclusion counts for nothing") {

    /**
     * Setup:
     *
     * Four assets captured on consecutive days: Alice in the Location Rome, Alice in "rome.jpg", "alice-rome.jpg", and another
     * asset in Rome.
     *
     * Assertions:
     *
     * Each AND-ed term adds the worth of its best source, an OR group is worth its best matching alternative, and an excluded
     * term filters without scoring.
     *
     * Edge cases:
     *
     * With only exclusions every match ties, and the tiebreakers alone decide the order.
     */
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5)
    val alice = addPerson("Alice")
    val aliceInRome = importAsset("one.jpg")
    val aliceAndFile = importAsset("rome.jpg")
    val fileOnly = importAsset("alice-rome.jpg")
    val inRome = importAsset("two.jpg")
    List(aliceInRome, aliceAndFile).foreach(addFace(alice, _))
    testApp.service.location.addAssets(rome.persistedId, Set(aliceInRome.persistedId, inRome.persistedId))
    takenAt(aliceInRome, "2026-09-01T10:00:00")
    takenAt(aliceAndFile, "2026-09-02T10:00:00")
    takenAt(fileOnly, "2026-09-03T10:00:00")
    takenAt(inRome, "2026-09-04T10:00:00")

    // Person 5 + Location 4, 5 + file 1, 1 + 1: the older capture leads because it is the better match
    ranked("alice rome") shouldBe List(aliceInRome, aliceAndFile, fileOnly).map(_.persistedId)
    // Alice is 5 however many alternatives match, so the newer capture leads; then the Location, then the file
    ranked("alice OR rome") shouldBe List(aliceAndFile, aliceInRome, inRome, fileOnly).map(_.persistedId)
    ranked("alice -two") shouldBe List(aliceAndFile, aliceInRome, fileOnly).map(_.persistedId)
    ranked("alice OR -one") shouldBe List(aliceAndFile, aliceInRome, fileOnly, inRome).map(_.persistedId)
    ranked("-two") shouldBe List(fileOnly, aliceAndFile, aliceInRome).map(_.persistedId)
  }

  test("Relevance: equal matches read newest capture first, the undated ones last, then by ID, page after page") {

    /**
     * Setup:
     *
     * Six assets that "beach" matches equally: three capture times, two of the assets sharing the middle one, and two undated.
     *
     * Assertions:
     *
     * Equal matches read newest capture first, undated last, and by ID within a tie; pages of two and of five walked by cursor
     * cut the same order.
     */
    val assets = (1 to 6).map(n => importAsset(s"beach-$n.jpg").persistedId).toList
    val List(older, newest, twinA, twinB, undatedA, undatedB) = assets: @unchecked
    val imported = OffsetDateTime.parse("2026-09-07T12:00:00Z")
    testContext.setAssetDates(older, Some(LocalDateTime.parse("2026-09-04T10:00:00")), imported)
    testContext.setAssetDates(newest, Some(LocalDateTime.parse("2026-09-06T10:00:00")), imported)
    List(twinA, twinB).foreach(testContext.setAssetDates(_, Some(LocalDateTime.parse("2026-09-05T10:00:00")), imported))
    List(undatedA, undatedB).foreach(testContext.setAssetDates(_, None, imported))

    val expected = newest :: List(twinA, twinB).sorted ::: older :: List(undatedA, undatedB).sorted
    ranked("beach") shouldBe expected
    ranked("beach", rpp = 2) shouldBe expected
    ranked("beach", rpp = 5) shouldBe expected
  }

  /** The IDs a Search text matches, most relevant first, over every page walked by cursor */
  private def ranked(text: String, rpp: Int = PAGE_SIZE): List[String] = {
    def page(cursor: Option[SearchCursor]): SearchResult =
      search(new SearchQuery(text = Some(text), rpp = rpp, searchSort = List(SearchSort.Relevance), cursor = cursor))

    List
      .unfold(Option(page(None)))(_.map(current => (current, current.nextCursor.map(cursor => page(Some(cursor))))))
      .flatMap(_.records.map(_.persistedId))
  }

  /** Gives the asset a capture time, the tiebreaker of equally relevant matches */
  private def takenAt(asset: Asset, taken: String): Unit =
    testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(taken)), OffsetDateTime.parse("2026-09-07T12:00:00Z"))

  /** A range of whole days from ISO dates; an end left out is open */
  private def days(from: String = "", to: String = ""): Option[DateRange] = {
    def day(iso: String): Option[LocalDate] = Option.when(iso.nonEmpty)(LocalDate.parse(iso))
    Some(DateRange(day(from), day(to)))
  }

  /** The IDs inside the date ranges, once the page's total and the exact count agree with the page */
  private def foundWithin(takenRange: Option[DateRange] = None, importedRange: Option[DateRange] = None): Set[String] = {
    val query = new SearchQuery(rpp = PAGE_SIZE, takenRange = takenRange, importedRange = importedRange)
    val result = search(query)
    result.total shouldBe Some(result.records.size)
    count(query) shouldBe result.records.size
    result.records.map(_.persistedId).toSet
  }

  private def found(text: String): Set[String] =
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some(text))).records.map(_.persistedId).toSet

  /** The people a one-term Search text resolves to */
  private def personHits(text: String): Set[String] =
    testApp.service.search
      .resolveText(SearchText.parse(text).value)
      .groups
      .head
      .alternatives
      .head
      .ids
      .getOrElse(SearchSource.Person, Set())

  private def importAsset(fileName: String, folder: Option[Folder] = None, metadata: UserMetadata = UserMetadata()): Asset =
    testApp.service.library.addAsset(
      testContext.makeAssetWithData(Some(testContext.makeAsset(filename = fileName, folder = folder, userMetadata = metadata))))

  private def addPerson(name: String): Person = testApp.service.person.addPerson(Person(name = Some(name)))

  /** A Face of the person in the asset; the vector is irrelevant to search */
  private def addFace(person: Person, asset: Asset): Face =
    testContext.addTestFace(person, asset, Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(Random.nextFloat()))

  /** The fields of an asset's metadata parameter rows, one per indexed value, sorted */
  private def parameterFields(asset: Asset): List[String] =
    testApp.txManager.asReadOnly {
      query("SELECT field_id FROM metadata_parameter WHERE asset_id = ?", asset.persistedId).map(_("field_id").toString).sorted
    }

  /**
   * The `search_document` rows `write` inserted or updated: PostgreSQL's per-transaction table statistics, or SQLite's change
   * count, which is exact because an asset without metadata changes no other table
   */
  private def documentRowsWrittenBy(write: => Unit): Long =
    testApp.txManager.withTransaction {
      def written: Long =
        if (isPostgres)
          query("SELECT n_tup_ins + n_tup_upd AS n FROM pg_stat_xact_user_tables WHERE relname = 'search_document'")
            .head("n")
            .toString
            .toLong
        else query("SELECT total_changes() AS n").head("n").toString.toLong

      val before = written
      write
      written - before
    }

  /** Every flat search reads a bounded page; one of this size holds every fixture */
  private val PAGE_SIZE = 100

  /** The default view and sort of the grid */
  private val browsingView = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false, FieldConst.Asset.IS_TRIAGED -> false)
  private val byImport = List(SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))

  /** A flat page's statement, for its query plan */
  private def flatPage(query: SearchQuery): SqlStr =
    SearchQueries.flat(searchDialect, query, RequestContext.getRepository.persistedId)

  /** The repository view with the triaged assets added to it: no triage flag is tested */
  private val withTriagedView = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false)

  /** An asset imported and captured at noon UTC on 15 June 2024, for [[seedCopiesOverDays]] to copy */
  private def persistAssetOfNoon(): Asset = {
    val asset = testContext.persistAsset()
    val noon = "2024-06-15T12:00:00"
    testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(noon)), OffsetDateTime.parse(s"${noon}Z"))
    asset
  }

  /**
   * For `atScale`: 5,000 copies imported a minute apart over the three and a half days before the asset, each then captured at
   * its import time by UTC's wall clock, so the capture days differ too
   */
  private def seedCopiesOverDays(template: Asset): Unit = {
    seedCopies(template.persistedId, copies = 5000, folders = 500)
    val importTime = if (isPostgres) "created_at AT TIME ZONE 'UTC'" else "created_at"
    update(
      s"UPDATE asset SET original_created_at = $importTime WHERE repository_id = ?",
      RequestContext.getRepository.persistedId)
  }

  test("Advanced search options: the names that have the typed text, by path or by name, people with their cover and count") {

    /**
     * Setup:
     *
     * Nested folders Trips › 2024 › Lisbon and a folder "Lisbon office"; Locations Lisbon and Porto in the Category Portugal, and
     * Sintra in none. Named people with Faces on a sorted asset, a triaged asset, both, or neither, and four people a Search text
     * does not name: hidden, bad match, unnamed, and one merged away.
     *
     * Assertions:
     *
     * With nothing typed the folder options are every folder by its path, parents first; the Location options every Location with
     * its Category before it; and the people options the named, visible, live people the search finds something for, each with
     * their cover and the number of assets counted in the chosen view. Typed text offers the names with a word starting with it.
     *
     * Edge cases:
     *
     * The root folder and a Category are no option, and text naming a Category or an ancestor folder offers nothing below it. The
     * people a Search text does not name are never offered, nor is the person with no Face: the people offered counting every
     * asset are exactly those `SearchDao.searchNames` reads, less that one. A person with no triaged asset is no option among the
     * triaged assets.
     */
    val trips = testApp.service.folder.add("Trips")
    val year = testApp.service.folder.add("2024", parentId = trips.id)
    val lisbonFolder = testApp.service.folder.add("Lisbon", parentId = year.id)
    val office = testApp.service.folder.add("Lisbon office")
    val portugal = testApp.service.location.addCategory("Portugal")
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
    val porto = testApp.service.location.addLocation("Porto", 41.15, -8.61, Some(portugal.persistedId))
    val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)
    val sorted = importAsset("one.jpg")
    val triaged = testContext.persistAsset(isTriaged = true)
    val alice = addPerson("Alice Liddell")
    val face = addFace(alice, sorted)
    val alan = addPerson("Alan Lisle")
    addFace(alan, sorted)
    addFace(alan, triaged)
    val alma = addPerson("Alma Triaged")
    addFace(alma, triaged)
    val alto = addPerson("Alto Nowhere")
    testApp.service.person.merge(dest = alma, source = addPerson("Alva Merged"))
    val hidden = addPerson("Lisa Hidden")
    addFace(hidden, sorted)
    testApp.service.person.setVisibility(hidden, isHidden = true)
    val badMatch = addPerson("Lise Badmatch")
    addFace(badMatch, sorted)
    testApp.service.person.markAsBadMatch(badMatch)
    addFace(testApp.service.person.addPerson(Person()), sorted)
    val options = testApp.service.search

    options.folderOptions("") shouldBe List(
      NamedPath(office.persistedId, List("Lisbon office")),
      NamedPath(trips.persistedId, List("Trips")),
      NamedPath(year.persistedId, List("Trips", "2024")),
      NamedPath(lisbonFolder.persistedId, List("Trips", "2024", "Lisbon"))
    )
    options.folderOptions("lis").map(_.id) shouldBe List(office.persistedId, lisbonFolder.persistedId)
    options.folderOptions("trips").map(_.id) shouldBe List(trips.persistedId)
    options.folderOptions("").map(_.label).last shouldBe "Trips › 2024 › Lisbon"

    options.locationOptions("") shouldBe List(
      NamedPath(lisbon.persistedId, List("Portugal", "Lisbon")),
      NamedPath(porto.persistedId, List("Portugal", "Porto")),
      NamedPath(sintra.persistedId, List("Sintra"))
    )
    options.locationOptions("lis").map(_.id) shouldBe List(lisbon.persistedId)
    options.locationOptions("portugal") shouldBe empty

    def people(typed: String, counted: CountedAssets = CountedAssets.Sorted): List[(String, Int)] =
      options.peopleOptions(typed, counted).map(listed => listed.person.persistedId -> listed.visibleCount)

    people("") shouldBe List(alan.persistedId -> 1, alice.persistedId -> 1)
    people("", CountedAssets.All) shouldBe List(alan.persistedId -> 2, alice.persistedId -> 1, alma.persistedId -> 1)
    people("", CountedAssets.Triaged) shouldBe List(alan.persistedId -> 1, alma.persistedId -> 1)
    options.peopleOptions("liddell", CountedAssets.Sorted).map(_.person.coverFaceId) shouldBe List(Some(face.persistedId))
    people("lis").map(_._1) shouldBe List(alan.persistedId)
    people("al").map(_._1) shouldBe List(alan.persistedId, alice.persistedId)
    people("al", CountedAssets.All).map(_._1) shouldBe List(alan.persistedId, alice.persistedId, alma.persistedId)
    people("al", CountedAssets.Triaged).map(_._1) shouldBe List(alan.persistedId, alma.persistedId)
    people("liddell").map(_._1) shouldBe List(alice.persistedId)
    people("liddell", CountedAssets.Triaged) shouldBe empty
    people("alto", CountedAssets.All) shouldBe empty
    people(PersonService.UNKNOWN_NAME_PREFIX, CountedAssets.All) shouldBe empty

    val searchable =
      testApp.txManager.asReadOnly(testApp.DAO.search.searchNames(Set(SearchSource.Person)))(SearchSource.Person).map(_.id).toSet
    people("", CountedAssets.All).map(_._1).toSet shouldBe searchable - alto.persistedId
  }

  test("Advanced search options and filter names read the names they use, and a Search text every name source") {

    /**
     * Setup:
     *
     * A folder, a Location in a Category, an album, and a named person with a Face on one asset.
     *
     * Assertions:
     *
     * By the DAO's TRACE line per candidate read, the folder options read the folders alone, the Location options the Locations
     * and Categories, and the people options and filter names (read by ID) no candidates. Resolving a Search text reads every
     * name source in one read.
     */
    val folder = testApp.service.folder.add("Trips")
    val portugal = testApp.service.location.addCategory("Portugal")
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
    testApp.service.album.add("Best")
    val alice = addPerson("Alice")
    addFace(alice, importAsset("one.jpg"))
    val options = testApp.service.search

    def sources(names: SearchSource*): List[Set[String]] = List(names.map(_.toString).toSet)

    candidatesReadDuring(options.folderOptions("tri").map(_.id) shouldBe List(folder.persistedId)) shouldBe
      sources(SearchSource.Folder)
    candidatesReadDuring(options.locationOptions("lis").map(_.id) shouldBe List(lisbon.persistedId)) shouldBe
      sources(SearchSource.Location, SearchSource.Category)
    candidatesReadDuring(options.peopleOptions("ali", CountedAssets.Sorted).size shouldBe 1) shouldBe Nil
    candidatesReadDuring {
      val names = options.filterNames(Some(folder.persistedId), List(alice.persistedId), List(lisbon.persistedId))
      names.folder.map(_.path) shouldBe Some(List("Trips"))
      names.people.map(_.persistedId) shouldBe List(alice.persistedId)
      names.locations.map(_.path) shouldBe List(List("Portugal", "Lisbon"))
    } shouldBe Nil
    candidatesReadDuring(options.resolveText(SearchText.parse("alice").value)) shouldBe sources(
      SearchSource.Person,
      SearchSource.Location,
      SearchSource.Category,
      SearchSource.Folder,
      SearchSource.Album)
  }

  /** The sources of each name-candidate read during the block, a set per read, from `SearchDao.searchNames`'s TRACE line */
  private def candidatesReadDuring(block: => Unit): List[Set[String]] = {
    val logger = LoggerFactory.getLogger(testApp.DAO.search.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    val additive = logger.isAdditive
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    // The lines go to this appender alone, not on to the console
    logger.setAdditive(false)
    try block
    finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
      logger.setAdditive(additive)
    }
    val prefix = "Search name candidates: "
    appender.list.asScala.toList.map(_.getFormattedMessage).filter(_.startsWith(prefix)).map {
      line => line.stripPrefix(prefix).split(", ").map(_.takeWhile(_ != '=')).toSet
    }
  }

  test("Advanced search filter names: the names behind a search's IDs, in the order given, less those that name nothing") {

    /**
     * Setup:
     *
     * Two repositories. The first has a nested folder, a Location in a Category and one in none, and the people Alice, a hidden
     * one, an unnamed one, one merged into Alice and a bad match; the second a folder, a Location and a person of its own.
     *
     * Assertions:
     *
     * The folder is named by its path, the Locations by Category and name, and the people are read whole, each list in the order
     * given. A hidden or unnamed person is named, since a search can be scoped to them.
     *
     * Edge cases:
     *
     * No entry for an unknown ID, the other repository's IDs, the person merged away, the bad match, a Category given as a
     * Location, or the root folder. `PersonService.getLive` reads the same people, and none for no ID.
     */
    val first = testContext.repository
    val trips = testApp.service.folder.add("Trips")
    val folder = testApp.service.folder.add("Lisbon", parentId = trips.id)
    val portugal = testApp.service.location.addCategory("Portugal")
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
    val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)
    val alice = addPerson("Alice")
    val zed = testApp.service.person.setVisibility(addPerson("Zed"), isHidden = true)
    val unnamed = testApp.service.person.addPerson(Person())
    val gone = addPerson("Gone")
    testApp.service.person.merge(dest = alice, source = gone)
    val bad = testApp.service.person.markAsBadMatch(addPerson("Bad"))

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val theirFolder = testApp.service.folder.add("Theirs")
    val theirLocation = testApp.service.location.addLocation("Theirs", 1, 2)
    val theirPerson = addPerson("Theirs")
    switchContextRepo(first)
    def unknown = java.util.UUID.randomUUID.toString

    val live = List(zed, unnamed, alice).map(_.persistedId)
    val names = testApp.service.search.filterNames(
      folderId = Some(folder.persistedId),
      personIds = List(unknown, live(0), theirPerson.persistedId, live(1), gone.persistedId, bad.persistedId, live(2)),
      locationIds = List(sintra.persistedId, theirLocation.persistedId, portugal.persistedId, lisbon.persistedId, unknown)
    )
    names.folder shouldBe Some(NamedPath(folder.persistedId, List("Trips", "Lisbon")))
    names.people.map(_.persistedId) shouldBe live
    names.people.head.isHidden shouldBe true
    names.locations shouldBe List(
      NamedPath(sintra.persistedId, List("Sintra")),
      NamedPath(lisbon.persistedId, List("Portugal", "Lisbon")))

    for (folderId <- List(unknown, theirFolder.persistedId, first.rootFolderId)) withClue(s"folder $folderId: ") {
      testApp.service.search.filterNames(Some(folderId), Nil, Nil) shouldBe SearchFilterNames(None, Nil, Nil)
    }
    testApp.service.search.filterNames(None, Nil, Nil) shouldBe SearchFilterNames(None, Nil, Nil)

    testApp.service.person
      .getLive(Set(alice, zed, unnamed, gone, bad, theirPerson).map(_.persistedId) + unknown)
      .map(_.persistedId)
      .toSet shouldBe live.toSet
    testApp.service.person.getLive(Set.empty) shouldBe empty
  }

  test("Indexing writes open their own transaction") {

    /**
     * Setup:
     *
     * An asset and a KEYWORD field, with the search service's index writes called directly, outside any transaction.
     *
     * Assertions:
     *
     * Adding a metadata value and reindexing a rename each write the Search document on their own.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))
    val asset = testContext.persistAsset()

    val withValue = asset.copy(userMetadata = UserMetadata(Map(field.persistedId -> Set("giraffe"))))
    testApp.service.search.addMetadataValue(withValue, field, "giraffe")
    documentBody(asset).get should include("giraffe")

    val renamed = asset.copy(fileName = "zebra.jpg")
    testApp.service.search.reindexAsset(renamed)
    documentBody(asset).get should include("zebra")
  }

  test("Search reads open their own transaction") {

    /**
     * Setup:
     *
     * One asset with coordinates, and the search service's reads called directly, outside any transaction.
     *
     * Assertions:
     *
     * Each read (flat, counts, map cells and bounds, grouped) finds the asset on its own.
     */
    val asset = testContext.persistAsset()
    testContext.setAssetCoordinates(asset.persistedId, latitude = 10, longitude = 20)
    val q = new SearchQuery(rpp = PAGE_SIZE)

    testApp.service.search.search(q, scopeFingerprint = "test").records.map(_.persistedId) shouldBe List(asset.persistedId)
    testApp.service.search.count(q) shouldBe 1
    testApp.service.search.cappedCount(q) shouldBe 1
    testApp.service.search.mapCells(q, BoundingBox(-90, -180, 90, 180), zoom = 0).cells.map(_.count).sum shouldBe 1
    testApp.service.search.mapBounds(q).map(_.count) shouldBe Some(1)

    val grouped = new SearchQuery(
      rpp = PAGE_SIZE,
      searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    testApp.service.search.searchGrouped(grouped, scopeFingerprint = "test").assets.map(_.persistedId) shouldBe
      List(asset.persistedId)
  }

  private def documentBody(asset: Asset): Option[String] =
    testApp.txManager.asReadOnly {
      query("SELECT body FROM search_document WHERE asset_id = ?", asset.persistedId).headOption.map(_("body").toString)
    }

}
