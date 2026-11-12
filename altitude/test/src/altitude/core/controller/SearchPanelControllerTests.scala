package altitude.core.controller

import altitude.test.TestContext
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.models.Person
import altitude.core.models.Repository
import altitude.core.util.Util

/** The advanced search panel's routes: the options its comboboxes are offered, and its form filled in from a search */
@DoNotDiscover class SearchPanelControllerTests extends ControllerTestCore {

  private val fields = Api.Field.Search

  /**
   * What a fragment says of an option, or of a chosen one in the panel's form: the ID and the label a chip shows, which its
   * element carries, the URL of the cover Face it shows, whether the element is a listbox option, and the text it shows
   */
  private case class Carried(id: String, label: String, coverUrl: Option[String], isOption: Boolean, text: String)

  /** Every element of `html` that carries an option's ID, in document order */
  private def carriedIn(html: String): List[Carried] =
    html.split("""(?=<\w+[^>]*\bdata-option-id=)""").toList.filter(_.contains("data-option-id=")).map {
      element =>
        val tag = element.takeWhile(_ != '>')
        def attribute(name: String): Option[String] = s"""\\b$name="([^"]*)"""".r.findFirstMatchIn(tag).map(_.group(1))
        Carried(
          id = attribute("data-option-id").get,
          label = attribute("data-option-label").get,
          coverUrl = """<img\b[^>]*\bclass="cover"[^>]*\bsrc="([^"]*)"""".r.findFirstMatchIn(element).map(_.group(1)),
          isOption = attribute("role").contains("option"),
          text = element.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim
        )
    }

  /** The value of the form's input named `name` */
  private def inputValue(html: String, name: String): String = {
    val input = s"""<input\\b[^>]*\\bname="$name"[^>]*>""".r.findFirstIn(html).get
    """\bvalue="([^"]*)"""".r.findFirstMatchIn(input).map(_.group(1)).getOrElse("")
  }

  private def get(host: String, repoId: String, path: String, params: Map[String, String] = Map.empty) =
    requests.get(
      s"$host/htmx/search/r/$repoId/$path",
      params = params,
      headers = Map("Accept-Encoding" -> "identity"),
      cookies = testContext.cookies,
      check = false)

  /** The options of `kind` (folders, people or locations) for the typed text, each a listbox option */
  private def options(host: String, repoId: String, kind: String, typed: Option[String] = None): List[Carried] = {
    val response = get(host, repoId, s"options/$kind", typed.map(fields.QUERY_TEXT -> _).toMap)
    response.statusCode shouldBe 200
    response.headers("content-type").head should include("text/html")
    val carried = carriedIn(response.text())
    carried.filterNot(_.isOption) shouldBe empty
    """role="option"""".r.findAllIn(response.text()).size shouldBe carried.size
    carried
  }

  test("Folder options are the folders whose name has the typed text, each by its path, in path order") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folders Archive, Trips with 2023 and 2024 below it and Lisbon below 2024, Work with
     * "Lisbon office" below it, and the folders "Élodie" and "beachSunset".
     *
     * Assertions:
     *
     * With nothing typed, the options are every folder in path order, a folder directly before the folders below it, each
     * carrying its ID and labelled by its path from the top-level folder down. Typed text is matched as the Search text matches a
     * name: "lis" offers the two folders with a word starting with it, whatever its case, "office lis" the one folder with both
     * words, "elo" the folder written with an accent, and "sun" the folder written with a camelCase hump.
     *
     * Edge cases:
     *
     * The root folder is never an option. Text inside a word ("bon") offers nothing, text with no usable term offers every
     * folder, and text naming a folder ("trips") offers that folder alone, not the folders below it.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val folders = testApp.service.folder
        val archive = folders.add("Archive")
        val trips = folders.add("Trips")
        val year2023 = folders.add("2023", parentId = trips.id)
        val year2024 = folders.add("2024", parentId = trips.id)
        val lisbon = folders.add("Lisbon", parentId = year2024.id)
        val work = folders.add("Work")
        val office = folders.add("Lisbon office", parentId = work.id)
        val elodie = folders.add("Élodie")
        val sunset = folders.add("beachSunset")
        def offered(typed: String): List[String] = options(host, repoId, "folders", Some(typed)).map(_.id)

        val all = options(host, repoId, "folders")
        // The order of the folder tree: by name key, which folds case and no accent
        all.map(_.label) shouldBe List(
          "Archive",
          "beachSunset",
          "Trips",
          "Trips › 2023",
          "Trips › 2024",
          "Trips › 2024 › Lisbon",
          "Work",
          "Work › Lisbon office",
          "Élodie")
        all.map(_.id) shouldBe
          List(archive, sunset, trips, year2023, year2024, lisbon, work, office, elodie).map(_.persistedId)
        all.map(_.id) should not contain testContext.repository.rootFolderId
        all.find(_.id == lisbon.persistedId).get.text should include("Lisbon")

        offered("lis") shouldBe List(lisbon.persistedId, office.persistedId)
        offered("LIS") shouldBe List(lisbon.persistedId, office.persistedId)
        offered("office lis") shouldBe List(office.persistedId)
        offered("elo") shouldBe List(elodie.persistedId)
        offered("sun") shouldBe List(sunset.persistedId)

        offered("bon") shouldBe empty
        offered("- OR") shouldBe all.map(_.id)
        offered("trips") shouldBe List(trips.persistedId)
    }
  }

  test("People options are the searchable people, each with their cover, name and number of assets, by name") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the named people Alice Anders, on two assets, and Alan Bo, Bob Alto and Carol, on one of
     * them; and five people a search does not offer: one with no name, the hidden "Alina Hidden", "Alba Bad" marked as a bad
     * match, "Alma Merged" merged into Carol, and "Alto Noface", named, with no Face on any asset.
     *
     * Assertions:
     *
     * With nothing typed, the options are the four named, visible, live people by name, each carrying their ID and name and
     * showing their cover Face; Alice's shows hers and her two assets, Alan's his one asset. "al" offers the three with a word of
     * their name starting with it.
     *
     * Edge cases:
     *
     * The person with no name, the hidden one, the bad match, the one merged away and the one a search finds nothing for are
     * never options, typed or not. No option carries its cover's URL as an attribute: a new chip copies the cover it shows.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val people = testApp.service.person
        val shared = testContext.persistAsset()
        def onShared(person: Person): Person = {
          testContext.addTestFace(person, shared, TestContext.unitVector(1))
          person
        }
        val alice = onShared(people.addPerson(Person(name = Some("Alice Anders"))))
        testContext.addTestFace(alice, testContext.persistAsset(), TestContext.unitVector(1))
        val alan = onShared(people.addPerson(Person(name = Some("Alan Bo"))))
        val bob = onShared(people.addPerson(Person(name = Some("Bob Alto"))))
        val carol = onShared(people.addPerson(Person(name = Some("Carol"))))
        val unnamed = onShared(people.addPerson(Person()))
        val hidden = people.setVisibility(onShared(people.addPerson(Person(name = Some("Alina Hidden")))), isHidden = true)
        val badMatch = people.markAsBadMatch(onShared(people.addPerson(Person(name = Some("Alba Bad")))))
        val merged = people.addPerson(Person(name = Some("Alma Merged")))
        people.merge(dest = carol, source = merged)
        val noFace = people.addPerson(Person(name = Some("Alto Noface")))
        val leftOut = List(unnamed, hidden, badMatch, merged, noFace).map(_.persistedId)

        val all = options(host, repoId, "people")
        all.map(_.label) shouldBe List("Alan Bo", "Alice Anders", "Bob Alto", "Carol")
        all.map(_.id) shouldBe List(alan, alice, bob, carol).map(_.persistedId)

        val aliceOption = all.find(_.id == alice.persistedId).get
        val cover = people.getById(alice.persistedId).coverFaceId.get
        aliceOption.coverUrl shouldBe Some(Util.contentUrl(Const.DataStore.FACE, cover))
        aliceOption.text should include("Alice Anders")
        aliceOption.text should include("2 assets")
        val alanOption = all.find(_.id == alan.persistedId).get
        alanOption.coverUrl shouldBe defined
        alanOption.text should include("1 asset")
        (alanOption.text should not).include("1 assets")
        (get(host, repoId, "options/people").text() should not).include("data-option-cover-url")

        val typed = options(host, repoId, "people", Some("al"))
        typed.map(_.id) shouldBe List(alan, alice, bob).map(_.persistedId)

        for (name <- List("unknown", "alina", "alba", "alma", "alto")) withClue(s"$name: ") {
          options(host, repoId, "people", Some(name)).map(_.id).intersect(leftOut) shouldBe empty
        }
    }
  }

  test("A People option shows the number of assets a search of the Person finds, with triaged items hidden or shown") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the named people Alice, on two sorted and three triaged assets, Bella, on two triaged
     * assets alone, and Cara, on one sorted asset with her count raised to 1,840.
     *
     * Assertions:
     *
     * With no `triaged` parameter Alice's option shows 2 assets, what the search finds, and Bella, whose search finds nothing, is
     * no option, as the People tab lists neither more for Alice nor Bella at all. With `triaged=true` Alice shows 5 assets and
     * Bella is offered with 2. Cara's count is grouped, "1,840". The panel's form asked for with `triaged=true` gives its People
     * field the options route with it, and without it the route alone.
     *
     * Edge cases:
     *
     * Only `true` shows triaged items: `triaged=false` reads as the default does. A value that is no boolean is a 400.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val people = testApp.service.person
        val alice = people.addPerson(Person(name = Some("Alice")))
        testContext.addTestFacesAndAssets(alice, assetCount = 2)
        testContext.addTestFacesAndAssets(alice, assetCount = 3, isTriaged = true)
        val bella = people.addPerson(Person(name = Some("Bella")))
        testContext.addTestFacesAndAssets(bella, assetCount = 2, isTriaged = true)
        val cara = people.addPerson(Person(name = Some("Cara")))
        testContext.addTestFacesAndAssets(cara)
        people.increment(cara.persistedId, FieldConst.Person.NUM_OF_FACES, 1839)

        def peopleOptions(params: Map[String, String]): Map[String, String] = {
          val response = get(host, repoId, "options/people", params)
          response.statusCode shouldBe 200
          carriedIn(response.text()).map(option => option.label -> option.text).toMap
        }

        for (params <- List(Map.empty[String, String], Map("triaged" -> "false"))) withClue(s"$params: ") {
          val hidden = peopleOptions(params)
          hidden.keySet shouldBe Set("Alice", "Cara")
          hidden("Alice") should include("2 assets")
          hidden("Cara") should include("1,840 assets")
        }

        val shown = peopleOptions(Map("triaged" -> "true"))
        shown.keySet shouldBe Set("Alice", "Bella", "Cara")
        shown("Alice") should include("5 assets")
        shown("Bella") should include("2 assets")
        peopleOptions(Map("triaged" -> "true", fields.QUERY_TEXT -> "bel")).keySet shouldBe Set("Bella")
        peopleOptions(Map(fields.QUERY_TEXT -> "bel")) shouldBe empty

        get(host, repoId, "options/people", Map("triaged" -> "maybe")).statusCode shouldBe 400

        val route = s"""data-options-url="/htmx/search/r/$repoId/options/people"""
        get(host, repoId, "panel", Map("triaged" -> "true")).text() should include(route + """?triaged=true"""")
        get(host, repoId, "panel").text() should include(route + '"')
    }
  }

  test("Location options are the Locations, each with its Category as a prefix, by name") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the Category Portugal holding the Locations Lisbon and Porto, the Category "Lisboa
     * region" holding Cascais, and the Location Sintra in no Category.
     *
     * Assertions:
     *
     * With nothing typed, the options are the four Locations by their own names, each carrying its ID and labelled by its
     * Category and its name, or by its name alone in no Category. "po" offers Porto.
     *
     * Edge cases:
     *
     * A Category is never an option, and text naming one ("lis", which Lisbon and the Category "Lisboa region" both have) offers
     * the Location whose own name has it and not the Locations of the Category.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val locations = testApp.service.location
        val portugal = locations.addCategory("Portugal")
        val region = locations.addCategory("Lisboa region")
        val lisbon = locations.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
        val porto = locations.addLocation("Porto", 41.15, -8.61, Some(portugal.persistedId))
        val cascais = locations.addLocation("Cascais", 38.7, -9.42, Some(region.persistedId))
        val sintra = locations.addLocation("Sintra", 38.8, -9.39)

        val all = options(host, repoId, "locations")
        all.map(_.label) shouldBe List("Lisboa region › Cascais", "Portugal › Lisbon", "Portugal › Porto", "Sintra")
        all.map(_.id) shouldBe List(cascais, lisbon, porto, sintra).map(_.persistedId)
        all.find(_.id == lisbon.persistedId).get.text should (include("Portugal").and(include("Lisbon")))

        options(host, repoId, "locations", Some("po")).map(_.id) shouldBe List(porto.persistedId)
        options(host, repoId, "locations", Some("lis")).map(_.id) shouldBe List(lisbon.persistedId)
        options(host, repoId, "locations", Some("portugal")) shouldBe empty
    }
  }

  test("Options stop at 50, the first by name or by path") {

    /**
     * Setup:
     *
     * A logged-in user's repository with 55 folders, 55 named people, each with a Face on its one asset, and 55 Locations, each
     * named with its number, 01 to 55.
     *
     * Assertions:
     *
     * Each options route answers 50 options, the first 50 in order, both with nothing typed and with text every name has.
     *
     * Edge cases:
     *
     * Text that only the last five names have offers those five: the cap is on what matches, not on what is read.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val numbers = (1 to 55).map(number => f"$number%02d").toList
        val asset = testContext.persistAsset()
        for (number <- numbers) {
          testApp.service.folder.add(s"Folder $number")
          val person = testApp.service.person.addPerson(Person(name = Some(s"Person $number")))
          testContext.addTestFace(person, asset, TestContext.unitVector(1))
          testApp.service.location.addLocation(s"Location $number", 1, 2)
        }

        for ((kind, name) <- List("folders" -> "Folder", "people" -> "Person", "locations" -> "Location"))
          withClue(s"$kind: ") {
            val first50 = numbers.take(Const.Search.MAX_FILTER_OPTIONS).map(number => s"$name $number")
            options(host, repoId, kind).map(_.label) shouldBe first50
            options(host, repoId, kind, Some(name.toLowerCase)).map(_.label) shouldBe first50
            options(host, repoId, kind, Some("5")).map(_.label) shouldBe (50 to 55).map(number => s"$name $number").toList
          }
    }
  }

  test("Options are the Library's own, and a name is shown as text") {

    /**
     * Setup:
     *
     * A logged-in user with two repositories. The first has the folder "Mine", the person "Mine Too" and the Location "Mine
     * Also"; the second a folder, a person and a Location each named "Theirs", and a folder whose name is HTML markup. Each
     * person has a Face on an asset of their repository.
     *
     * Assertions:
     *
     * Through the first repository each options route offers that repository's one name and nothing of the second's, typed or
     * not; through the second, the second's alone.
     *
     * Edge cases:
     *
     * The folder named with markup is offered with its name escaped, in its label and in its text.
     */
    val mine = testContext.persistRepository()
    login()

    withServer(App) {
      host =>
        def addPersonWithFace(name: String, library: Repository): Unit = {
          val person = testApp.service.person.addPerson(Person(name = Some(name)))
          testContext.addTestFace(person, testContext.persistAsset(repository = Some(library)), TestContext.unitVector(1))
        }
        testApp.service.folder.add("Mine")
        addPersonWithFace("Mine Too", mine)
        testApp.service.location.addLocation("Mine Also", 1, 2)

        val theirs = addLibrary("Second library", testContext.user)
        testContext.cookies = cookiesOf(host, testContext.user)
        testApp.service.repository.switchContextToRepository(theirs)
        testApp.service.folder.add("Theirs")
        testApp.service.folder.add("<b>Theirs & co</b>")
        addPersonWithFace("Theirs", theirs)
        testApp.service.location.addLocation("Theirs", 1, 2)
        testApp.service.repository.switchContextToRepository(mine)

        for (kind <- List("folders", "people", "locations"); typed <- List(None, Some("theirs"), Some("mine")))
          withClue(s"$kind $typed: ") {
            val offered = options(host, mine.persistedId, kind, typed).map(_.label)
            offered.filter(_.contains("Theirs")) shouldBe empty
            if (!typed.contains("theirs")) offered should have size 1
          }

        for (kind <- List("folders", "people", "locations")) withClue(s"$kind in the second: ") {
          val offered = options(host, theirs.persistedId, kind).map(_.label)
          offered should not be empty
          offered.filter(_.contains("Mine")) shouldBe empty
        }

        val markup = get(host, theirs.persistedId, "options/folders", Map(fields.QUERY_TEXT -> "co")).text()
        (markup should not).include("<b>")
        carriedIn(markup).map(_.label) shouldBe List("&lt;b&gt;Theirs &amp; co&lt;/b&gt;")
    }
  }

  test("The panel's form is filled in from a search's parameters, with the names behind its IDs") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Lisbon under 2024 under Trips, the people Alice, with a Face, and Bob, the
     * Location Lisbon in the Category Portugal and the Location Sintra.
     *
     * Assertions:
     *
     * The panel asked for with a search's parameters is a form whose inputs hold them under their parameter names: the text, the
     * folder, the people and the Locations as given, comma-separated, the people match and the four dates. The folder is a chip
     * labelled by its path, each person a chip with their name, Alice's showing her cover Face, and each Location a chip labelled
     * with its Category, the chips of a field in the order its IDs were given in.
     *
     * Edge cases:
     *
     * The panel asked for with no parameter holds no text, no chip and no date, and matches all people. The parameters of a
     * search that are no filter of the panel (the view, the sort, the layout) are ignored.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trips = testApp.service.folder.add("Trips")
        val year = testApp.service.folder.add("2024", parentId = trips.id)
        val folder = testApp.service.folder.add("Lisbon", parentId = year.id)
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        testContext.addTestFace(alice, testContext.persistAsset(), TestContext.unitVector(1))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val portugal = testApp.service.location.addCategory("Portugal")
        val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
        val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)
        val personIds = s"${bob.persistedId},${alice.persistedId}"
        val locationIds = s"${sintra.persistedId},${lisbon.persistedId}"

        val response = get(
          host,
          repoId,
          "panel",
          Map(
            fields.QUERY_TEXT -> "beach -sunset",
            fields.FOLDER_ID -> folder.persistedId,
            fields.PERSON_ID -> personIds,
            fields.LOCATION_ID -> locationIds,
            fields.PEOPLE_MATCH -> "any",
            fields.TAKEN_FROM -> "2024-06-01",
            fields.TAKEN_TO -> "2024-06-30",
            fields.IMPORTED_FROM -> "2025-01-02",
            fields.IMPORTED_TO -> "2025-01-03",
            fields.VIEW -> Const.Search.View.TRIAGE,
            fields.SORT -> "filename0",
            fields.LAYOUT -> Const.Search.Layout.MAP
          )
        )
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        val form = response.text()
        form should include("<form")

        inputValue(form, fields.QUERY_TEXT) shouldBe "beach -sunset"
        inputValue(form, fields.FOLDER_ID) shouldBe folder.persistedId
        inputValue(form, fields.PERSON_ID) shouldBe personIds
        inputValue(form, fields.LOCATION_ID) shouldBe locationIds
        inputValue(form, fields.PEOPLE_MATCH) shouldBe "any"
        inputValue(form, fields.TAKEN_FROM) shouldBe "2024-06-01"
        inputValue(form, fields.TAKEN_TO) shouldBe "2024-06-30"
        inputValue(form, fields.IMPORTED_FROM) shouldBe "2025-01-02"
        inputValue(form, fields.IMPORTED_TO) shouldBe "2025-01-03"

        val chips = carriedIn(form)
        chips.filter(_.isOption) shouldBe empty
        chips.map(chip => chip.id -> chip.label) shouldBe List(
          folder.persistedId -> "Trips › 2024 › Lisbon",
          bob.persistedId -> "Bob",
          alice.persistedId -> "Alice",
          sintra.persistedId -> "Sintra",
          lisbon.persistedId -> "Portugal › Lisbon"
        )
        val cover = testApp.service.person.getById(alice.persistedId).coverFaceId.get
        chips.find(_.id == alice.persistedId).get.coverUrl shouldBe Some(Util.contentUrl(Const.DataStore.FACE, cover))
        chips.find(_.id == bob.persistedId).get.coverUrl shouldBe None

        val blank = get(host, repoId, "panel").text()
        carriedIn(blank) shouldBe empty
        inputValue(blank, fields.PEOPLE_MATCH) shouldBe "all"
        for (
          name <- List(
            fields.QUERY_TEXT,
            fields.FOLDER_ID,
            fields.PERSON_ID,
            fields.LOCATION_ID,
            fields.TAKEN_FROM,
            fields.TAKEN_TO,
            fields.IMPORTED_FROM,
            fields.IMPORTED_TO)
        ) withClue(s"$name: ")(inputValue(blank, name) shouldBe "")
    }
  }

  test("The panel drops an ID that names nothing in the Library, and keeps a person a search can be scoped to") {

    /**
     * Setup:
     *
     * A logged-in user with two repositories. The first has the folder Trips, the person Alice, a hidden person Zed, a person
     * with no name, the person "Gone" merged into Alice, the Category Portugal and the Location Sintra; the second a folder, a
     * person and a Location of its own.
     *
     * Assertions:
     *
     * The panel asked for with Alice, Zed, the person with no name, Sintra and Trips beside IDs that name nothing renders a chip
     * for each of the five and holds their IDs alone in its inputs: a hidden person and one with no name are people a search can
     * be scoped to, so they stay.
     *
     * Edge cases:
     *
     * Dropped, from the chips and from the inputs alike: an ID that does not exist, the second repository's folder, person and
     * Location, the person merged away, a Category given as a Location, and the root folder, which is the whole Library. The form
     * names nothing of the second repository.
     */
    val mine = testContext.persistRepository()
    val repoId = mine.persistedId
    login()

    withServer(App) {
      host =>
        val people = testApp.service.person
        val trips = testApp.service.folder.add("Trips")
        val alice = people.addPerson(Person(name = Some("Alice")))
        val zed = people.setVisibility(people.addPerson(Person(name = Some("Zed"))), isHidden = true)
        val unnamed = people.addPerson(Person())
        val gone = people.addPerson(Person(name = Some("Gone")))
        people.merge(dest = alice, source = gone)
        val portugal = testApp.service.location.addCategory("Portugal")
        val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)

        val theirs = addLibrary("Second library", testContext.user)
        testApp.service.repository.switchContextToRepository(theirs)
        val theirFolder = testApp.service.folder.add("Quxtheirs folder")
        val theirPerson = people.addPerson(Person(name = Some("Quxtheirs Eve")))
        val theirLocation = testApp.service.location.addLocation("Quxtheirs place", 1, 2)
        testApp.service.repository.switchContextToRepository(mine)
        def unknown = UUID.randomUUID.toString

        val kept = List(alice, zed, unnamed).map(_.persistedId)
        val form = get(
          host,
          repoId,
          "panel",
          Map(
            fields.FOLDER_ID -> trips.persistedId,
            fields.PERSON_ID -> (List(unknown, kept(0), theirPerson.persistedId, kept(1), gone.persistedId) :+ kept(2))
              .mkString(","),
            fields.LOCATION_ID -> List(theirLocation.persistedId, portugal.persistedId, sintra.persistedId, unknown).mkString(",")
          )
        ).text()

        inputValue(form, fields.FOLDER_ID) shouldBe trips.persistedId
        inputValue(form, fields.PERSON_ID) shouldBe kept.mkString(",")
        inputValue(form, fields.LOCATION_ID) shouldBe sintra.persistedId
        carriedIn(form).map(_.id) shouldBe trips.persistedId :: kept ::: List(sintra.persistedId)
        carriedIn(form).find(_.id == zed.persistedId).get.label shouldBe "Zed"
        (form should not).include("Quxtheirs")

        for (folderId <- List(unknown, theirFolder.persistedId, mine.rootFolderId)) withClue(s"folder $folderId: ") {
          val dropped = get(host, repoId, "panel", Map(fields.FOLDER_ID -> folderId))
          dropped.statusCode shouldBe 200
          inputValue(dropped.text(), fields.FOLDER_ID) shouldBe ""
          carriedIn(dropped.text()) shouldBe empty
          (dropped.text() should not).include("Quxtheirs")
        }
    }
  }

  test("The panel refuses the parameters a search refuses") {

    /**
     * Setup:
     *
     * A logged-in user's empty repository.
     *
     * Assertions:
     *
     * The panel asked for with a date that is not an ISO day, a range whose start is after its end, a people match that is
     * neither `all` nor `any`, or eleven people in one parameter is a plain-text 400, as the search with them is.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val refused = List(
          Map(fields.TAKEN_FROM -> "june"),
          Map(fields.IMPORTED_FROM -> "2024-06-02", fields.IMPORTED_TO -> "2024-06-01"),
          Map(fields.PEOPLE_MATCH -> "some"),
          Map(fields.PERSON_ID -> List.fill(11)(UUID.randomUUID.toString).mkString(","))
        )
        for (params <- refused) withClue(s"$params: ") {
          val response = get(host, repoId, "panel", params)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
        }
    }
  }
}
