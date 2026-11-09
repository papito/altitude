package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Person

@DoNotDiscover class PeopleActionControllerTests extends ControllerTestCore {

  /** Renames the person through the name editor's route */
  private def rename(host: String, repoId: String, person: Person, name: String): requests.Response =
    requests.put(
      s"$host/htmx/people/r/$repoId/p/${person.persistedId}/name/edit",
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(ujson.Obj(Api.Field.Person.NAME -> name, Api.Field.ID -> person.persistedId)),
      cookies = testContext.cookies,
      check = false
    )

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
     * name, Mia's marked hidden and each carrying its cover; the merged-away and the bad-match people are absent.
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
     * under the group's heading, and then the list of the rest in its order, Mia and the two unnamed people, so no Person is
     * listed twice. Asked without a Face, the list has no group and no suggested mark; asked with an unknown Face, it is a 404.
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
        optionIds shouldBe List(adam, zoe, mia, current, unknown).map(_.persistedId)
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

  test("A person's results title is served on its own, with the person's count") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" with Faces on two assets.
     *
     * Assertions:
     *
     * The title route answers 200 with the person's results title inner, naming Bob and his two faces; an unknown person is a
     * 404.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        testContext.addTestFacesAndAssets(bob, assetCount = 2)

        val response =
          requests.get(s"$host/htmx/people/r/$repoId/p/${bob.persistedId}/title", cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="personName"""")
        response.text() should include("Bob")
        response.text() should include("2 faces")

        requests
          .get(s"$host/htmx/people/r/$repoId/p/${java.util.UUID.randomUUID}/title", cookies = testContext.cookies, check = false)
          .statusCode shouldBe 404
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
}
