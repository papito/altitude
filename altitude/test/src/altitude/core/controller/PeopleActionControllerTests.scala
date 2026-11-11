package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.models.Person

@DoNotDiscover class PeopleActionControllerTests extends ControllerTestCore {

  /** Renames the person through the name editor's route */
  private def rename(host: String, repoId: String, person: Person, name: String): requests.Response =
    renameInline(host, repoId, person.persistedId, ujson.Obj(Api.Field.Person.NAME -> name))

  /** Sends `body` to the name editor's route of the Person with this ID, who is named by the path alone */
  private def renameInline(host: String, repoId: String, personId: String, body: ujson.Obj): requests.Response =
    requests.put(
      s"$host/htmx/people/r/$repoId/p/$personId/name/edit",
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(body),
      cookies = testContext.cookies,
      check = false
    )

  /** The People tab's list for the buckets, with the query parameters given */
  private def listPeople(
      host: String,
      repoId: String,
      buckets: String,
      params: Map[String, String] = Map.empty): requests.Response =
    requests.get(
      s"$host/htmx/people/r/$repoId/list",
      params = params + ("buckets" -> buckets),
      cookies = testContext.cookies,
      check = false)

  /** The rename dialog the People tab's pencil opens for the Person with this ID, with the query parameters given */
  private def renameDialog(
      host: String,
      repoId: String,
      personId: String,
      params: Map[String, String] = Map.empty): requests.Response =
    requests.get(
      s"$host/htmx/people/r/$repoId/dialogs/rename-person",
      params = params + ("personId" -> personId),
      cookies = testContext.cookies,
      check = false)

  /** Renames the Person with this ID through the rename dialog's route, as its form submits, from `pane` when one is given */
  private def renameInDialog(
      host: String,
      repoId: String,
      personId: String,
      name: String,
      pane: Option[String] = None): requests.Response = {
    val body = ujson.Obj(Api.Field.Person.NAME -> name)
    pane.foreach(paneName => body(Api.Field.Person.PANE) = ujson.Str(paneName))
    requests.put(
      s"$host/htmx/people/r/$repoId/p/$personId/rename",
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(body),
      cookies = testContext.cookies,
      check = false
    )
  }

  /** The response is the rename dialog's form again, sent to replace the submitted one, with an error in it */
  private def renameDialogRefused(response: requests.Response): Unit = {
    response.statusCode shouldBe 200
    response.headers("hx-retarget") shouldBe Seq("this")
    response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
    response.text() should include("""id="renamePerson"""")
    response.text() should include("""class="error"""")
  }

  /** The response completes the rename dialog's operation: an empty body, sent to replace nothing */
  private def renameDialogSaved(response: requests.Response): Unit = {
    response.statusCode shouldBe 200
    response.text() shouldBe ""
    response.headers.keys should not contain "hx-retarget"
  }

  /** The number the list shows on the Person's cell, when the Person is listed */
  private def listedCount(html: String, person: Person): Option[Int] =
    s"""(?s)data-person-id="${person.persistedId}".*?<span class="count"> \\((-?\\d+)\\)</span>""".r
      .findFirstMatchIn(html)
      .map(_.group(1).toInt)

  test("The assignable people are the live people who are not a bad match, named first, Hidden marked") {

    /**
     * Setup:
     *
     * A logged-in user's repository with People named "Zoe" and "adam", a hidden "Mia", two unnamed People with a Face each, one
     * more merged into the first of them, and a bad-match Person.
     *
     * Assertions:
     *
     * The assignable list answers 200 with one option per person in the order adam, Mia, Zoe, then the unnamed people by sort
     * name, Mia's marked hidden, the named people's marked named and the unnamed people's not, and each carrying its cover; the
     * merged-away and the bad-match people are absent.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val zoe = testApp.service.person.addPerson(Person(name = Some("Zoe")))
        val adam = testApp.service.person.addPerson(Person(name = Some("adam")))
        val mia = testApp.service.person.addPerson(Person(name = Some("Mia")))
        val unknown1 = testApp.service.person.addPerson(Person())
        val mergedAway = testApp.service.person.addPerson(Person())
        val unknown2 = testApp.service.person.addPerson(Person())
        val badMatch = testApp.service.person.addPerson(Person())
        List(zoe, adam, mia, unknown1, mergedAway, unknown2, badMatch).foreach(testContext.addTestFacesAndAssets(_))
        testApp.service.person.setVisibility(mia, isHidden = true)
        testApp.service.person.merge(dest = unknown1, source = mergedAway)
        testApp.service.person.markAsBadMatch(badMatch)

        val response = requests.get(s"$host/htmx/people/r/$repoId/assignable", cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        val html = response.text()
        val optionIds = """data-person-id="([^"]+)"""".r.findAllMatchIn(html).map(_.group(1)).toList
        optionIds shouldBe List(adam, mia, zoe, unknown1, unknown2).map(_.persistedId)
        html should include(s"""data-person-id="${mia.persistedId}"""")
        """data-person-id="[^"]+"\s+data-person-name="Mia"\s+data-person-hidden""".r.findFirstIn(html).isDefined shouldBe true
        // An option's opening tag, which carries whether its Person is named: the combobox's merge or move
        def optionTag(person: Person): String =
          s"""<li role="option"\\s+data-person-id="${person.persistedId}"[^>]*>""".r.findFirstIn(html).get
        List(adam, mia, zoe).foreach(named => optionTag(named) should include("""data-person-named="true""""))
        List(unknown1, unknown2).foreach(nameless => (optionTag(nameless) should not).include("data-person-named"))
        html should include(s"/${Const.DataStore.FACE}/${testApp.service.person.getById(adam.persistedId).coverFaceId.get}")
    }
  }

  test("With a Face, the assignable people open with the Suggested group of the people nearest it, each listed once") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Face of an unnamed Person at a synthetic embedding, Faces of "Zoe" about 0.29 away and
     * of "adam" about 0.05 away, the Face of a hidden "Mia" about 1 away, past the threshold, and an unnamed Person with a Face
     * at a random embedding.
     *
     * Assertions:
     *
     * Asked with the Face, the list answers 200 with the Suggested group first, adam then Zoe, each option marked suggested and
     * under the group's heading, and then the list of the rest in its order, Mia and the other unnamed Person, so no Person is
     * listed twice and the Face's own Person, who is no target, is no option. Asked without a Face, the list holds every live
     * Person, the Face's own among them, with no group and no suggested mark; asked with an unknown Face, it is a 404.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val vector = TestContext.unitVector(31)
        val other = TestContext.unitVector(32)
        val asset = testContext.persistAsset()
        val current = testApp.service.person.addPerson(Person())
        val face = testContext.addTestFace(current, asset, vector)
        val zoe = testApp.service.person.addPerson(Person(name = Some("Zoe")))
        testContext.addTestFace(zoe, asset, TestContext.between(vector, other, 1))
        val adam = testApp.service.person.addPerson(Person(name = Some("adam")))
        testContext.addTestFace(adam, asset, TestContext.between(vector, other, 3))
        val mia = testApp.service.person.addPerson(Person(name = Some("Mia")))
        testContext.addTestFace(mia, asset, other)
        testApp.service.person.setVisibility(mia, isHidden = true)
        val unknown = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(unknown)

        def list(params: Map[String, String]): requests.Response =
          requests.get(s"$host/htmx/people/r/$repoId/assignable", params = params, cookies = testContext.cookies, check = false)

        val response = list(Map("faceId" -> face.persistedId))

        response.statusCode shouldBe 200
        val html = response.text()
        val optionIds = """data-person-id="([^"]+)"""".r.findAllMatchIn(html).map(_.group(1)).toList
        optionIds shouldBe List(adam, zoe, mia, unknown).map(_.persistedId)
        (html should not).include(current.persistedId)
        val suggestedIds = """data-person-id="([^"]+)"[^>]*data-suggested""".r.findAllMatchIn(html).map(_.group(1)).toList
        suggestedIds shouldBe List(adam, zoe).map(_.persistedId)
        html should include("""role="group" aria-labelledby="suggestedPeopleHeading"""")
        html should include("""id="suggestedPeopleHeading"""")

        val plain = list(Map.empty).text()
        """data-person-id="([^"]+)"""".r.findAllMatchIn(plain).map(_.group(1)).toList shouldBe
          List(adam, mia, zoe, current, unknown).map(_.persistedId)
        (plain should not).include("data-suggested")
        (plain should not).include("""role="group"""")

        list(Map("faceId" -> java.util.UUID.randomUUID.toString)).statusCode shouldBe 404
    }
  }

  test("A person's results title is served on its own, with no face count") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with Faces on two assets, and a second repository of theirs with a
     * Person named "Eve".
     *
     * Assertions:
     *
     * The title route answers 200 with the person's results title inner, naming Bob with no subtitle and no face count, which the
     * toolbar's Total says; an unknown person is a 404, and so is the other repository's Eve, asked for through the first
     * repository, whose name the answer does not carry.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFacesAndAssets(bob, assetCount = 2)
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testApp.service.repository.switchContextToRepository(repo)

        val response =
          requests.get(s"$host/htmx/people/r/$repoId/p/${bob.persistedId}/title", cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="personName"""")
        response.text() should include("Bob")
        (response.text() should not).include("faces")
        (response.text() should not).include("""class="subtitle"""")

        requests
          .get(s"$host/htmx/people/r/$repoId/p/${java.util.UUID.randomUUID}/title", cookies = testContext.cookies, check = false)
          .statusCode shouldBe 404
        val foreign =
          requests.get(s"$host/htmx/people/r/$repoId/p/${eve.persistedId}/title", cookies = testContext.cookies, check = false)
        foreign.statusCode shouldBe 404
        (foreign.text() should not).include("Eve")
    }
  }

  test("A Person's count on the People list is what a search finds, with triaged items hidden or shown") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with one sorted and two triaged assets, and a hidden Person named
     * "Mia" with one sorted and one triaged asset.
     *
     * Assertions:
     *
     * With no `triaged` parameter Bob reads (1), and with `triaged=true` he reads (3). Mia's name link is named by its content:
     * her name, the number, (1) by default and (2) with `triaged=true`, and then a visually hidden ", hidden".
     *
     * Edge cases:
     *
     * Only `true` shows triaged items: `triaged=false` reads as the default does.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFacesAndAssets(bob)
        testContext.addTestFacesAndAssets(bob, assetCount = 2, isTriaged = true)
        val mia = testApp.service.person.addPerson(Person(name = Some("Mia")))
        testContext.addTestFacesAndAssets(mia)
        testContext.addTestFacesAndAssets(mia, isTriaged = true)
        testApp.service.person.setVisibility(mia, isHidden = true)
        val buckets = s"${Const.PeopleBucket.MATCHED},${Const.PeopleBucket.HIDDEN}"

        val hidden = listPeople(host, repoId, buckets)
        hidden.statusCode shouldBe 200
        listedCount(hidden.text(), bob) shouldBe Some(1)
        hidden.text() should include("""Mia<span class="count"> (1)</span><span class="visually-hidden">, hidden</span>""")

        val shown = listPeople(host, repoId, buckets, Map("triaged" -> "true"))
        shown.statusCode shouldBe 200
        listedCount(shown.text(), bob) shouldBe Some(3)
        shown.text() should include("""Mia<span class="count"> (2)</span><span class="visually-hidden">, hidden</span>""")

        val notShown = listPeople(host, repoId, buckets, Map("triaged" -> "false"))
        notShown.statusCode shouldBe 200
        listedCount(notShown.text(), bob) shouldBe Some(1)
    }
  }

  test("A Person with only triaged assets is left off the People list until triaged items are shown") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Ann" with a sorted asset and a Person named "Eve" with two triaged
     * assets only.
     *
     * Assertions:
     *
     * By default the list holds Ann and not Eve; with `triaged=true` it holds both, Eve reading (2).
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val ann = testApp.service.person.addPerson(Person(name = Some("Ann")))
        testContext.addTestFacesAndAssets(ann)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testContext.addTestFacesAndAssets(eve, assetCount = 2, isTriaged = true)

        val hidden = listPeople(host, repoId, Const.PeopleBucket.MATCHED)
        hidden.statusCode shouldBe 200
        listedCount(hidden.text(), ann) shouldBe Some(1)
        (hidden.text() should not).include(eve.persistedId)

        val shown = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("triaged" -> "true"))
        shown.statusCode shouldBe 200
        listedCount(shown.text(), ann) shouldBe Some(1)
        listedCount(shown.text(), eve) shouldBe Some(2)
    }
  }

  test("A People list the triage switch emptied says so, and a list with no one to show does not") {

    /**
     * Setup:
     *
     * A logged-in user's repository, first with no people at all and then with one Person, "Eve", who has only triaged assets.
     *
     * Assertions:
     *
     * With no people the list says no one is in the buckets. With Eve, the list by default says everyone is only in triaged
     * items, and not that no one is there; with `triaged=true` it lists Eve and says neither.
     *
     * Edge cases:
     *
     * The Hidden bucket, which Eve is not in, holds no one with her there, so it says no one is there and nothing of triage.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    val noOne = "No one in the buckets switched on"
    val onlyTriaged = "Everyone here is only in triaged items. Turn on Show triaged items to see them."

    withServer(App) {
      host =>
        val noPeople = listPeople(host, repoId, Const.PeopleBucket.MATCHED).text()
        noPeople should include(noOne)
        (noPeople should not).include(onlyTriaged)

        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testContext.addTestFacesAndAssets(eve, assetCount = 2, isTriaged = true)

        val leftOff = listPeople(host, repoId, Const.PeopleBucket.MATCHED).text()
        leftOff should include(onlyTriaged)
        (leftOff should not).include(noOne)

        val shown = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("triaged" -> "true")).text()
        listedCount(shown, eve) shouldBe Some(2)
        (shown should not).include(onlyTriaged)
        (shown should not).include(noOne)

        val emptyBucket = listPeople(host, repoId, Const.PeopleBucket.HIDDEN).text()
        emptyBucket should include(noOne)
        (emptyBucket should not).include(onlyTriaged)
    }
  }

  test("A triaged parameter that is not a boolean is a 400 on the People list") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The list answers `triaged=nope` with a 400 for the value, not for a parameter it does not know, and the same request
     * without it with a 200.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val refused = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("triaged" -> "nope"))
        refused.statusCode shouldBe 400
        // Cask also answers 400 for a parameter the route does not declare
        (refused.text() should not).include("Unknown argument")
        listPeople(host, repoId, Const.PeopleBucket.MATCHED).statusCode shouldBe 200
    }
  }

  test("A Person's rename pencil and name link on the People list are anchored by IDs that carry the pane") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with a sorted asset.
     *
     * Assertions:
     *
     * Listed with `pane=main`, Bob's cell holds a pencil named "Rename Bob" that requests the rename dialog and names itself as
     * the dialog's anchor by an ID carrying the pane and Bob's ID. Its name link is the anchor of the scope dialog a click on the
     * face or the name opens in Triage: an ID carrying the pane and Bob's ID, with that anchor name, which both links name as
     * their dialog's anchor. Listed with `pane=duplicate`, both IDs carry that pane instead, so the two panes' copies of one
     * Person never share an ID.
     *
     * Edge cases:
     *
     * A list asked for with no `pane` is the Main pane's. A `pane` that is no pane is a 400 for the value, not for a parameter
     * the route does not know.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFacesAndAssets(bob)
        val mainAnchor = s"personRenameCtrl-main-${bob.persistedId}"
        val duplicateAnchor = s"personRenameCtrl-duplicate-${bob.persistedId}"
        val mainLink = s"personLink-main-${bob.persistedId}"
        val duplicateLink = s"personLink-duplicate-${bob.persistedId}"

        val main = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("pane" -> "main"))
        main.statusCode shouldBe 200
        main.text() should include(s"""id="$mainAnchor"""")
        main.text() should include(s"""data-app-dialog-anchor="$mainAnchor"""")
        main.text() should include("""aria-label="Rename Bob"""")
        main.text() should include(s"/htmx/people/r/$repoId/dialogs/rename-person")
        (main.text() should not).include(duplicateAnchor)
        main.text() should include(s"""id="$mainLink"""")
        main.text() should include(s"""style="anchor-name: --$mainLink"""")
        // The face's link and the name link
        s"""data-app-dialog-anchor="$mainLink"""".r.findAllIn(main.text()).size shouldBe 2
        (main.text() should not).include(duplicateLink)

        val duplicate = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("pane" -> "duplicate"))
        duplicate.statusCode shouldBe 200
        duplicate.text() should include(s"""id="$duplicateAnchor"""")
        duplicate.text() should include(s"""data-app-dialog-anchor="$duplicateAnchor"""")
        (duplicate.text() should not).include(mainAnchor)
        duplicate.text() should include(s"""id="$duplicateLink"""")
        (duplicate.text() should not).include(mainLink)

        val noPane = listPeople(host, repoId, Const.PeopleBucket.MATCHED)
        noPane.statusCode shouldBe 200
        noPane.text() should include(s"""id="$mainAnchor"""")

        val refused = listPeople(host, repoId, Const.PeopleBucket.MATCHED, Map("pane" -> "side"))
        refused.statusCode shouldBe 400
        // Cask also answers 400 for a parameter the route does not declare
        (refused.text() should not).include("Unknown argument")
    }
  }

  test("Choose person cover face modal renders without a width parameter") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one Person, who has one test Face on an asset.
     *
     * Assertions:
     *
     * The choose-cover-face modal, requested with only the person's ID, renders with its face selector.
     *
     * Edge cases:
     *
     * No width parameter is sent.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val person: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(person)

        val response = requests.get(
          s"$host/htmx/people/r/$repoId/modals/choose-person-cover-face",
          params = Map("personId" -> person.persistedId),
          cookies = testContext.cookies,
          check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="faceSelector"""")
    }
  }

  test("A Person with no cover shows a placeholder on the People list, in the results title and in the merge modal") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Alice", with Faces on three assets and no cover, as a cover's Face
     * deleted under it would leave her, and a Person named "Bob" with a Face and his cover.
     *
     * Assertions:
     *
     * The People list, Alice's results title and the merge modal of the two each render the no-cover placeholder in place of
     * Alice's face, and no face URL with no Face ID at its end; Bob's cover is still his face's URL.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val alice: Person = testApp.service.person.addPerson(Person(name = Some("Alice")))
        testContext.addTestFacesAndAssets(alice, assetCount = 3)
        testApp.service.person.updateById(alice.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> None))
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFacesAndAssets(bob)
        val bobsCover = testApp.service.person.getById(bob.persistedId).coverFaceId.get

        // The end of a face URL rendered with no Face ID
        val emptyFaceUrl = s"/${Const.DataStore.FACE}/" + '"'
        def get(path: String, params: Map[String, String] = Map.empty): String = {
          val response = requests.get(s"$host$path", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.text()
        }

        val list = get(s"/htmx/people/r/$repoId/list")
        val title = get(s"/htmx/people/r/$repoId/p/${alice.persistedId}/title")
        val modal = get(
          s"/htmx/people/r/$repoId/modals/merge",
          Map("mergeSourceId" -> alice.persistedId, "mergeDestId" -> bob.persistedId, "keepDirection" -> "true"))

        List("list" -> list, "title" -> title, "modal" -> modal).foreach {
          case (view, html) =>
            withClue(s"$view: ") {
              html should include("""class="no-cover"""")
              (html should not).include(emptyFaceUrl)
            }
        }
        list should include(s"/${Const.DataStore.FACE}/$bobsCover")
        modal should include(s"/${Const.DataStore.FACE}/$bobsCover")
    }
  }

  test("Renaming a person as an unnamed person is named shows the error under the field") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob".
     *
     * Assertions:
     *
     * Renaming him "Unknown 42" answers 200 with the name editor, the reserved-name error under its field and the name typed kept
     * in it; Bob keeps his name.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val response = rename(host, repoId, bob, "Unknown 42")

        response.statusCode shouldBe 200
        response.text() should include("""id="editPersonName"""")
        response.text() should include(s"""<div id="personNameError" class="error">${Const.Msg.Err.RESERVED_PERSON_NAME}</div>""")
        response.text() should include("""value="Unknown 42"""")

        val persistedBob: Person = testApp.service.person.getById(bob.persistedId)
        persistedBob.name shouldBe Some("Bob")
    }
  }

  test("Renaming a person to a name another person holds offers to merge into that person") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Persons named "Tessa" and "Bob".
     *
     * Assertions:
     *
     * Renaming Bob "tessa" answers 200 with the name editor, the error naming Tessa as she is stored, and a button that
     * dispatches the merge confirmation with Bob as the source, Tessa as the destination and the direction kept. Bob keeps his
     * name.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val response = rename(host, repoId, bob, "tessa")

        response.statusCode shouldBe 200
        val text = response.text()
        text should include("""id="editPersonName"""")
        text should include("Tessa already exists.")
        text should include("Merge into Tessa…")
        text should include(
          s"$$dispatch(window.Const.events.confirmPersonMerge, " +
            s"{ mergeSourceId: '${bob.persistedId}', mergeDestId: '${tessa.persistedId}', keepDirection: true })")

        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")
    }
  }

  test("Renaming a person to a name a hidden person holds says the holder is hidden") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a hidden Person named "Tessa" and a Person named "Bob".
     *
     * Assertions:
     *
     * Renaming Bob "TESSA" answers the editor with the error saying Tessa exists and is hidden, and the merge button.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        testApp.service.person.setVisibility(tessa, isHidden = true)
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val response = rename(host, repoId, bob, "TESSA")

        response.statusCode shouldBe 200
        response.text() should include("Tessa already exists and is hidden.")
        response.text() should include("Merge into Tessa…")
    }
  }

  test("Renaming a person to its own name in another case saves the new name") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Tessa".
     *
     * Assertions:
     *
     * Renaming her "TESSA" answers the name view, with no editor, showing "TESSA", and the stored name is "TESSA".
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))

        val response = rename(host, repoId, tessa, "TESSA")

        response.statusCode shouldBe 200
        (response.text() should not).include("""id="editPersonName"""")
        response.text() should include("TESSA")

        testApp.service.person.getById(tessa.persistedId).name shouldBe Some("TESSA")
    }
  }

  test("The rename dialog renders for a Person with the current name") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob".
     *
     * Assertions:
     *
     * The rename dialog asked for with Bob's ID answers 200 with an HTML document holding the dialog's form: a modal fragment
     * titled "Rename person", its name field holding "Bob", submitting to Bob's rename route, and announcing the name edit with
     * Bob's ID as its detail once it succeeds. Asked for with `pane=duplicate`, it returns focus to Bob's pencil in that pane and
     * its form sends the pane back.
     *
     * Edge cases:
     *
     * Asked for with no `pane`, the dialog is the Main pane's. A `pane` that is no pane is a 400.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val response = renameDialog(host, repoId, bob.persistedId)

        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        val text = response.text()
        text.startsWith("<!doctype html>") shouldBe true
        text should include("""id="renamePerson"""")
        text should include("""data-app-fragment="modal"""")
        text should include("""data-app-modal-title="Rename person"""")
        text should include("""value="Bob"""")
        text should include(s"""hx-put="/htmx/people/r/$repoId/p/${bob.persistedId}/rename"""")
        text should include("""data-app-success-event="PERSON_NAME_EDITED_EVENT"""")
        text should include(s"""data-app-success-detail='{"personId":"${bob.persistedId}"}'""")
        text should include(s"""data-app-dialog-return-focus="#personRenameCtrl-main-${bob.persistedId}"""")

        val duplicate = renameDialog(host, repoId, bob.persistedId, Map("pane" -> "duplicate"))
        duplicate.statusCode shouldBe 200
        duplicate.text() should include(s"""data-app-dialog-return-focus="#personRenameCtrl-duplicate-${bob.persistedId}"""")
        duplicate.text() should include("""name="pane" value="duplicate"""")

        renameDialog(host, repoId, bob.persistedId, Map("pane" -> "side")).statusCode shouldBe 400
    }
  }

  test("Saving a new name in the rename dialog returns an empty success") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob".
     *
     * Assertions:
     *
     * Renaming him "Robert" through the dialog's route answers 200 with an empty body that replaces nothing, which completes the
     * dialog, and the stored name is "Robert".
     *
     * Edge cases:
     *
     * A name sent with spaces around it, " Bobby ", is saved trimmed.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        renameDialogSaved(renameInDialog(host, repoId, bob.persistedId, "Robert"))
        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Robert")

        renameDialogSaved(renameInDialog(host, repoId, bob.persistedId, " Bobby "))
        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bobby")
    }
  }

  test("An invalid name re-renders the rename dialog with its error") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob".
     *
     * Assertions:
     *
     * Renaming him "Unknown 42" through the dialog's route answers with the dialog's form again, sent to replace the submitted
     * one, with the reserved-name error and the name typed kept in its field; Bob keeps his name.
     *
     * Edge cases:
     *
     * An empty name is refused the same way. A refusal of a form sent from the Duplicate pane names that pane's pencil again.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val reserved = renameInDialog(host, repoId, bob.persistedId, "Unknown 42")

        renameDialogRefused(reserved)
        reserved.text() should include(Const.Msg.Err.RESERVED_PERSON_NAME)
        reserved.text() should include("""value="Unknown 42"""")

        renameDialogRefused(renameInDialog(host, repoId, bob.persistedId, ""))

        val fromDuplicate = renameInDialog(host, repoId, bob.persistedId, "", pane = Some("duplicate"))
        renameDialogRefused(fromDuplicate)
        fromDuplicate.text() should include(s"""data-app-dialog-return-focus="#personRenameCtrl-duplicate-${bob.persistedId}"""")

        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")
    }
  }

  test("A name another Person holds offers the merge in the rename dialog, and says when the holder is hidden") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Persons named "Tessa" and "Bob"; Tessa is then hidden.
     *
     * Assertions:
     *
     * Renaming Bob "tessa" through the dialog's route answers with the dialog's form again, sent to replace the submitted one,
     * the name typed kept, the error naming Tessa as she is stored, and a button that dispatches the merge confirmation with Bob
     * as the source, Tessa as the destination, the direction kept and the Main pane, the pane of a form that sends none. With
     * Tessa hidden, the same rename says she exists and is hidden, and still offers the merge. Bob keeps his name throughout.
     *
     * Edge cases:
     *
     * A form sent from the Duplicate pane offers the merge with that pane.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

        val taken = renameInDialog(host, repoId, bob.persistedId, "tessa")

        renameDialogRefused(taken)
        taken.text() should include("""value="tessa"""")
        taken.text() should include("Tessa already exists.")
        taken.text() should include("Merge into Tessa…")
        taken.text() should include(
          s"$$dispatch(window.Const.events.confirmPersonMerge, " +
            s"{ mergeSourceId: '${bob.persistedId}', mergeDestId: '${tessa.persistedId}', keepDirection: true, pane: 'main' })")

        val takenFromDuplicate = renameInDialog(host, repoId, bob.persistedId, "tessa", pane = Some("duplicate"))
        renameDialogRefused(takenFromDuplicate)
        takenFromDuplicate.text() should include("keepDirection: true, pane: 'duplicate' })")

        testApp.service.person.setVisibility(tessa, isHidden = true)

        val takenByHidden = renameInDialog(host, repoId, bob.persistedId, "TESSA")

        renameDialogRefused(takenByHidden)
        takenByHidden.text() should include("Tessa already exists and is hidden.")
        takenByHidden.text() should include("Merge into Tessa…")

        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")
    }
  }

  test("A change of case alone saves in the rename dialog") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Tessa".
     *
     * Assertions:
     *
     * Renaming her "TESSA" through the dialog's route answers the empty success, with no offer to merge her into herself, and the
     * stored name is "TESSA".
     *
     * Edge cases:
     *
     * Saving the exact name she already has, "TESSA" again, is the empty success too, and the name stays.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))

        renameDialogSaved(renameInDialog(host, repoId, tessa.persistedId, "TESSA"))
        testApp.service.person.getById(tessa.persistedId).name shouldBe Some("TESSA")

        renameDialogSaved(renameInDialog(host, repoId, tessa.persistedId, "TESSA"))
        testApp.service.person.getById(tessa.persistedId).name shouldBe Some("TESSA")
    }
  }

  test("The rename dialog and its save are 404s for an unknown Person and for another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs with a Person named "Eve".
     *
     * Assertions:
     *
     * Asked through the first repository, the rename dialog and the rename itself answer 404 for a Person ID that does not exist
     * and for Eve's, the answers do not carry her name, and Eve keeps it.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testApp.service.repository.switchContextToRepository(repo)
        val unknownId = java.util.UUID.randomUUID.toString

        renameDialog(host, repoId, unknownId).statusCode shouldBe 404
        renameInDialog(host, repoId, unknownId, "Robert").statusCode shouldBe 404

        val foreignDialog = renameDialog(host, repoId, eve.persistedId)
        foreignDialog.statusCode shouldBe 404
        (foreignDialog.text() should not).include("Eve")
        val foreignRename = renameInDialog(host, repoId, eve.persistedId, "Robert")
        foreignRename.statusCode shouldBe 404
        (foreignRename.text() should not).include("Eve")

        testApp.service.repository.switchContextToRepository(otherRepo)
        testApp.service.person.getById(eve.persistedId).name shouldBe Some("Eve")
    }
  }

  test("The name editor renames the Person its path names, and is a 404 for an unknown Person and for another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Persons named "Bob" and "Ann", and a second repository of theirs with a Person named
     * "Eve".
     *
     * Assertions:
     *
     * A body holding the name alone, sent to Bob's name editor route, answers the name view and stores the name: the route needs
     * no ID in the body. Sent through the first repository to a Person ID that does not exist, and to Eve's, the rename answers
     * 404, the answer does not carry her name, and Eve keeps it.
     *
     * Edge cases:
     *
     * A body that also carries Ann's ID still renames Bob, the Person of the path, and leaves Ann as she was.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val ann: Person = testApp.service.person.addPerson(Person(name = Some("Ann")))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testApp.service.repository.switchContextToRepository(repo)

        val byPath = renameInline(host, repoId, bob.persistedId, ujson.Obj(Api.Field.Person.NAME -> "Robert"))
        byPath.statusCode shouldBe 200
        (byPath.text() should not).include("""id="editPersonName"""")
        byPath.text() should include("Robert")
        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Robert")

        val withAnotherId = renameInline(
          host,
          repoId,
          bob.persistedId,
          ujson.Obj(Api.Field.Person.NAME -> "Bobby", Api.Field.ID -> ann.persistedId))
        withAnotherId.statusCode shouldBe 200
        testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bobby")
        testApp.service.person.getById(ann.persistedId).name shouldBe Some("Ann")

        val unknownId = java.util.UUID.randomUUID.toString
        renameInline(host, repoId, unknownId, ujson.Obj(Api.Field.Person.NAME -> "Robert")).statusCode shouldBe 404

        val foreign = renameInline(host, repoId, eve.persistedId, ujson.Obj(Api.Field.Person.NAME -> "Robert"))
        foreign.statusCode shouldBe 404
        (foreign.text() should not).include("Eve")

        testApp.service.repository.switchContextToRepository(otherRepo)
        testApp.service.person.getById(eve.persistedId).name shouldBe Some("Eve")
    }
  }

  test("The rename dialog and its save are 404s for a merged-away Person and for a bad match") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" merged into a Person named "Tessa", and a Person named "Carl"
     * marked as a bad match.
     *
     * Assertions:
     *
     * The rename dialog asked for with Bob's ID, and with Carl's, answers 404 and not the dialog's form. A rename of either
     * through the dialog's route answers 404 too, and each keeps the name stored on their row.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val carl: Person = testApp.service.person.addPerson(Person(name = Some("Carl")))
        testApp.service.person.merge(dest = tessa, source = bob)
        testApp.service.person.markAsBadMatch(carl)

        List(bob -> "Bob", carl -> "Carl").foreach {
          (person, storedName) =>
            val dialog = renameDialog(host, repoId, person.persistedId)
            dialog.statusCode shouldBe 404
            (dialog.text() should not).include("""id="renamePerson"""")

            renameInDialog(host, repoId, person.persistedId, "Robert").statusCode shouldBe 404
            testApp.service.person.getById(person.persistedId).name shouldBe Some(storedName)
        }
    }
  }

  test("The name editor's rename is a 404 for a merged-away Person and for a bad match") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" merged into a Person named "Tessa", and a Person named "Carl"
     * marked as a bad match.
     *
     * Assertions:
     *
     * A rename of Bob, and of Carl, through the name editor's route answers 404, and each keeps the name stored on their row.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val carl: Person = testApp.service.person.addPerson(Person(name = Some("Carl")))
        testApp.service.person.merge(dest = tessa, source = bob)
        testApp.service.person.markAsBadMatch(carl)

        List(bob -> "Bob", carl -> "Carl").foreach {
          (person, storedName) =>
            rename(host, repoId, person, "Robert").statusCode shouldBe 404
            testApp.service.person.getById(person.persistedId).name shouldBe Some(storedName)
        }
    }
  }

  test("The merge modal keeps the requested direction when asked, and otherwise merges the person with fewer faces") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person with two test Faces and one with one.
     *
     * Assertions:
     *
     * The merge modal requested with the two-face person as the source and `keepDirection=true` merges that person into the
     * other. Requested the same way without `keepDirection`, it swaps them, merging the one-face person into the two-face one.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val larger: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(larger, assetCount = 2)
        val smaller: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(smaller)

        def modal(params: Map[String, String]): String = {
          val response = requests.get(
            s"$host/htmx/people/r/$repoId/modals/merge",
            params = Map("mergeSourceId" -> larger.persistedId, "mergeDestId" -> smaller.persistedId) ++ params,
            cookies = testContext.cookies,
            check = false
          )
          response.statusCode shouldBe 200
          response.text()
        }

        modal(Map("keepDirection" -> "true")) should include(s"/src/${larger.persistedId}/dest/${smaller.persistedId}")
        modal(Map.empty) should include(s"/src/${smaller.persistedId}/dest/${larger.persistedId}")
    }
  }

  test("The merge modal returns focus to the rename pencil of the pane it is asked with") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Persons named "Bob" and "Tessa".
     *
     * Assertions:
     *
     * The merge modal requested with Bob as the source, the direction kept and `pane=duplicate` answers 200 and names Bob's
     * rename pencil of the Duplicate pane as the control focus returns to. Requested with no `pane`, it names no control.
     *
     * Edge cases:
     *
     * A `pane` that is no pane is a 400 for the value, not for a parameter the route does not know.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))

        def modal(params: Map[String, String]): requests.Response =
          requests.get(
            s"$host/htmx/people/r/$repoId/modals/merge",
            params =
              Map("mergeSourceId" -> bob.persistedId, "mergeDestId" -> tessa.persistedId, "keepDirection" -> "true") ++ params,
            cookies = testContext.cookies,
            check = false
          )

        val fromPencil = modal(Map("pane" -> "duplicate"))
        fromPencil.statusCode shouldBe 200
        fromPencil.text() should include(s"""data-app-dialog-return-focus="#personRenameCtrl-duplicate-${bob.persistedId}"""")

        val plain = modal(Map.empty)
        plain.statusCode shouldBe 200
        (plain.text() should not).include("data-app-dialog-return-focus")

        val refused = modal(Map("pane" -> "side"))
        refused.statusCode shouldBe 400
        // Cask also answers 400 for a parameter the route does not declare
        (refused.text() should not).include("Unknown argument")
    }
  }

  test("Merging people returns no content and does not redirect to search results") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two People, each with one test Face on an asset of their own.
     *
     * Assertions:
     *
     * Merging one into the other answers 204 with no Location header, so nothing redirects. The client owns the search
     * parameters, so a merge reports only that it happened; showing the destination person is a normal search the browser runs
     * off the dialog's success event.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val sourcePerson: Person = testApp.service.person.addPerson(Person())
        val destPerson: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(sourcePerson)
        testContext.addTestFacesAndAssets(destPerson)

        val response = requests.put(
          s"$host/htmx/people/r/$repoId/src/${sourcePerson.persistedId}/dest/${destPerson.persistedId}",
          cookies = testContext.cookies,
          maxRedirects = 0,
          // The global compress decorator still labels the empty body as gzipped, which the client cannot unwrap
          autoDecompress = false,
          check = false
        )

        response.statusCode shouldBe 204
        response.headers.keys should not contain "location"
    }
  }

  /** A request of a People route through the repository, with no body */
  private def peopleRoute(
      method: requests.Requester,
      host: String,
      repoId: String,
      path: String,
      params: Map[String, String] = Map.empty): requests.Response =
    method(s"$host/htmx/people/r/$repoId/$path", params = params, cookies = testContext.cookies, check = false)

  test("Every People route that reads a Person by ID is a 404 for an unknown ID and for another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with a Face, and a second repository of theirs with a Person named
     * "Eve" with a Face.
     *
     * Assertions:
     *
     * Asked through the first repository for a Person ID that does not exist, and for Eve's, the choose-cover modal, the cover
     * image (given Eve's Face for Eve), the name view and its editor, the merge modal and the merge with Bob, either way round,
     * hide, show and the discard as a bad match all answer 404, and no answer carries Eve's name. Neither Eve's repository nor
     * Bob's changes.
     *
     * Edge cases:
     *
     * Bob's cover image set to Eve's Face, or to a Face ID that does not exist, is a 404 too.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFace(
          bob,
          testContext.insertAsset(testContext.makeAsset(repository = Some(repo))),
          TestContext.unitVector(1))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        val evesFace = testContext.addTestFace(
          eve,
          testContext.insertAsset(testContext.makeAsset(repository = Some(otherRepo))),
          TestContext.unitVector(2))
        testApp.service.repository.switchContextToRepository(repo)
        val ownBefore = testContext.rowsOf(repo)
        val othersBefore = testContext.rowsOf(otherRepo)
        val unknownId = java.util.UUID.randomUUID.toString
        val bobId = bob.persistedId

        for ((id, faceId) <- List(unknownId -> unknownId, eve.persistedId -> evesFace.persistedId)) withClue(s"[$id]: ") {
          val answers = List(
            peopleRoute(requests.get, host, repoId, "modals/choose-person-cover-face", Map("personId" -> id)),
            peopleRoute(requests.put, host, repoId, s"p/$id/cover-image", Map(Api.Field.FACE_ID -> faceId)),
            peopleRoute(requests.get, host, repoId, s"p/$id/name"),
            peopleRoute(requests.get, host, repoId, s"p/$id/name/edit"),
            peopleRoute(requests.get, host, repoId, "modals/merge", Map("mergeSourceId" -> id, "mergeDestId" -> bobId)),
            peopleRoute(requests.get, host, repoId, "modals/merge", Map("mergeSourceId" -> bobId, "mergeDestId" -> id)),
            peopleRoute(requests.put, host, repoId, s"src/$id/dest/$bobId"),
            peopleRoute(requests.put, host, repoId, s"src/$bobId/dest/$id"),
            peopleRoute(requests.put, host, repoId, s"p/$id/hide"),
            peopleRoute(requests.put, host, repoId, s"p/$id/show"),
            peopleRoute(requests.delete, host, repoId, s"p/$id"),
            peopleRoute(requests.put, host, repoId, s"p/$bobId/cover-image", Map(Api.Field.FACE_ID -> faceId))
          )
          for (answer <- answers) {
            answer.statusCode shouldBe 404
            (answer.text() should not).include("Eve")
          }
        }

        testContext.rowsOf(repo) shouldBe ownBefore
        testContext.rowsOf(otherRepo) shouldBe othersBefore
    }
  }

  test("A cover image that is another Person's Face, or a Face on an asset marked for purging, is a 400, and the cover stays") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with Faces on two assets, the first his cover and the second's
     * asset recycled and marked for purging, and a Person named "Ann" with a Face of her own.
     *
     * Assertions:
     *
     * Setting Bob's cover image to Ann's Face answers a plain-text 400 saying the Face is not his, and to his Face on the asset
     * marked for purging a 400 saying the Face is on an asset being purged. His cover is still his first Face.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val cover = testContext.addTestFace(bob, testContext.insertAsset(), TestContext.unitVector(1))
        // Read again, so the second Face finds the cover the first set
        val purging = testContext.addTestFace(
          testApp.service.person.getById(bob.persistedId),
          testContext.insertAsset(),
          TestContext.unitVector(2))
        testApp.service.library.recycleAssets(Set(purging.assetId.get))
        testApp.service.asset.updateById(purging.assetId.get, Map(FieldConst.Asset.IS_PURGED -> true))
        val ann = testApp.service.person.addPerson(Person(name = Some("Ann")))
        val annsFace = testContext.addTestFace(ann, testContext.insertAsset(), TestContext.unitVector(3))

        for (
          (face, message) <- List(annsFace -> Const.Msg.Err.COVER_FACE_NOT_THEIRS, purging -> Const.Msg.Err.COVER_FACE_PURGING)
        )
          withClue(s"[$message]: ") {
            val response =
              peopleRoute(
                requests.put,
                host,
                repoId,
                s"p/${bob.persistedId}/cover-image",
                Map(Api.Field.FACE_ID -> face.persistedId))
            response.statusCode shouldBe 400
            response.headers("content-type") shouldBe Seq("text/plain")
            response.text() shouldBe message
          }

        testApp.service.person.getById(bob.persistedId).coverFaceId shouldBe Some(cover.persistedId)
    }
  }

  test("The choose-cover modal offers the Person's Faces, but none on an asset marked for purging") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with Faces on three assets, the second's asset recycled and marked
     * for purging.
     *
     * Assertions:
     *
     * The choose-cover modal for Bob offers his Faces on the first and third assets, and not his Face on the asset marked for
     * purging, which could not be set as his cover.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val faces = (1 to 3).toList.map(n => testContext.addTestFace(bob, testContext.insertAsset(), TestContext.unitVector(n)))
        val purging = faces(1)
        testApp.service.library.recycleAssets(Set(purging.assetId.get))
        testApp.service.asset.updateById(purging.assetId.get, Map(FieldConst.Asset.IS_PURGED -> true))

        val modal = peopleRoute(requests.get, host, repoId, "modals/choose-person-cover-face", Map("personId" -> bob.persistedId))

        modal.statusCode shouldBe 200
        modal.text() should include(s"""data-face-id="${faces.head.persistedId}"""")
        modal.text() should include(s"""data-face-id="${faces(2).persistedId}"""")
        (modal.text() should not).include(s"""data-face-id="${purging.persistedId}"""")
    }
  }
}
