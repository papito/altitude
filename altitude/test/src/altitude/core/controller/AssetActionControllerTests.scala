package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import scala.util.Random
import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

/** The Face corrections of Asset detail over HTTP: the People section they answer with, and their status codes */
@DoNotDiscover class AssetActionControllerTests extends ControllerTestCore {

  private def face(person: Person, asset: Asset): Face =
    testContext.addTestFace(person, asset, Array.fill(512)(Random.nextFloat()), withFiles = true)

  private def changePerson(host: String, repoId: String, faceId: String, body: ujson.Obj): requests.Response =
    requests.put(
      s"$host/htmx/asset/r/$repoId/face/$faceId/person",
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(body),
      cookies = testContext.cookies,
      check = false
    )

  private def remove(host: String, repoId: String, faceId: String): requests.Response =
    requests.delete(s"$host/htmx/asset/r/$repoId/face/$faceId", cookies = testContext.cookies, check = false)

  private def rowOf(faceId: String): String = s"""data-face-id="$faceId""""

  test("Changing a Face's person answers the People section with the Face under the new person and the result message") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an image holding a Face of Alice and a Face of Bob, Alice with another Face elsewhere.
     *
     * Assertions:
     *
     * Moving Alice's Face to Bob by his ID answers 200 with the People section: both rows carry Bob's ID and name, the root
     * carries "Moved to Bob" and names no deleted person; Bob's count is still one, the image being his already.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val image = testContext.persistAsset()
        val moving = face(alice, image)
        val bobs = face(bob, image)
        face(alice, testContext.persistAsset())

        val response = changePerson(host, repoId, moving.persistedId, ujson.Obj(Api.Field.PERSON_ID -> bob.persistedId))

        response.statusCode shouldBe 200
        val html = response.text()
        html should include("""id="detailPanelPeople"""")
        html should include(rowOf(moving.persistedId))
        html should include(rowOf(bobs.persistedId))
        html should include(s"""data-person-id="${bob.persistedId}"""")
        (html should not).include(s"""data-person-id="${alice.persistedId}"""")
        html should include(s"""data-app-result-message="${Const.Msg.FaceCorrection.MOVED.format("Bob")}"""")
        (html should not).include("data-app-deleted-person-id")
        testApp.service.person.getById(bob.persistedId).numOfFaces shouldBe 1
    }
  }

  test("Changing a Face to a name creates the person, and a source left with no Face is reported deleted") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an unnamed Person whose only Face is on an image.
     *
     * Assertions:
     *
     * Moving the Face to "Carol" answers 200 with the row under a new Person named Carol, "Moved to Carol" and the deleted
     * source's ID in the root's data attributes; the source is gone.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val unknown = testApp.service.person.addPerson(Person())
        val f = face(unknown, testContext.persistAsset())

        val response = changePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.Person.NAME -> " Carol "))

        response.statusCode shouldBe 200
        val html = response.text()
        val carol = testApp.service.person.getLiveByNameKey("carol").get
        html should include(s"""data-person-id="${carol.persistedId}"""")
        html should include("""data-person-name="Carol"""")
        html should include(s"""data-app-result-message="${Const.Msg.FaceCorrection.MOVED.format("Carol")}"""")
        html should include(s"""data-app-deleted-person-id="${unknown.persistedId}"""")
        html should include(Const.Msg.FaceCorrection.PERSON_REMOVED.format(unknown.name.get))
        Try(testApp.service.person.getById(unknown.persistedId)).isSuccess shouldBe false
    }
  }

  test("Removing a Face answers the People section without it, and a second removal is not found") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an image holding a Face of Alice, who has another Face, and a Face of Bob, who has none.
     *
     * Assertions:
     *
     * Removing Alice's Face answers 200 with Bob's row alone and "Removed Alice"; removing it again answers 404, as does moving
     * it. Removing Bob's Face reports him deleted, and leaves the section with no rows.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val image = testContext.persistAsset()
        val alices = face(alice, image)
        val bobs = face(bob, image)
        face(alice, testContext.persistAsset())

        val response = remove(host, repoId, alices.persistedId)
        response.statusCode shouldBe 200
        (response.text() should not).include(rowOf(alices.persistedId))
        response.text() should include(rowOf(bobs.persistedId))
        response.text() should include(s"""data-app-result-message="${Const.Msg.FaceCorrection.REMOVED.format("Alice")}"""")
        (response.text() should not).include("data-app-deleted-person-id")

        remove(host, repoId, alices.persistedId).statusCode shouldBe 404
        changePerson(host, repoId, alices.persistedId, ujson.Obj(Api.Field.PERSON_ID -> bob.persistedId)).statusCode shouldBe 404

        val last = remove(host, repoId, bobs.persistedId)
        last.statusCode shouldBe 200
        (last.text() should not).include("data-face-id=")
        last.text() should include(s"""data-app-deleted-person-id="${bob.persistedId}"""")
        last.text() should include(Const.Msg.FaceCorrection.PERSON_REMOVED.format("Bob"))
    }
  }

  test("A Face of a recycled asset is refused as a conflict by both corrections") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person's Face on a recycled asset and another Person.
     *
     * Assertions:
     *
     * Moving and removing the Face both answer 409, and the Face keeps its person.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val owner = testApp.service.person.addPerson(Person())
        val other = testApp.service.person.addPerson(Person())
        val asset = testContext.persistAsset()
        val f = face(owner, asset)
        face(other, testContext.persistAsset())
        testApp.service.library.recycleAssets(Set(asset.persistedId))

        changePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.PERSON_ID -> other.persistedId)).statusCode shouldBe 409
        remove(host, repoId, f.persistedId).statusCode shouldBe 409
        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
    }
  }

  test("A change request names one target, a person by ID or a name of a valid length, and another person") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person's Face.
     *
     * Assertions:
     *
     * A body with neither a person nor a name, with both, with a one-letter name, with a reserved name, or naming the Face's own
     * person answers 400; one naming an unknown person answers 404. The Face keeps its person throughout.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val owner = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val f = face(owner, testContext.persistAsset())
        val other = testApp.service.person.addPerson(Person(name = Some("Bob")))

        changePerson(host, repoId, f.persistedId, ujson.Obj()).statusCode shouldBe 400
        changePerson(
          host,
          repoId,
          f.persistedId,
          ujson.Obj(Api.Field.PERSON_ID -> other.persistedId, Api.Field.Person.NAME -> "Carol")).statusCode shouldBe 400
        changePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.Person.NAME -> "C")).statusCode shouldBe 400
        changePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.Person.NAME -> "Unknown 3")).statusCode shouldBe 400
        changePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.PERSON_ID -> owner.persistedId)).statusCode shouldBe 400
        changePerson(
          host,
          repoId,
          f.persistedId,
          ujson.Obj(Api.Field.PERSON_ID -> java.util.UUID.randomUUID.toString)).statusCode shouldBe 404

        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
    }
  }

  test("The corrections require a login") {

    /**
     * Setup:
     *
     * A repository with a Person's Face, and no cookie sent.
     *
     * Assertions:
     *
     * Both correction routes answer 401 without a login, and the Face keeps its person.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val headers = Map("Accept" -> "application/json", "Content-Type" -> "application/json")

    withServer(App) {
      host =>
        val owner = testApp.service.person.addPerson(Person())
        val f = face(owner, testContext.persistAsset())

        requests
          .put(
            s"$host/htmx/asset/r/$repoId/face/${f.persistedId}/person",
            headers = headers,
            data = ujson.write(ujson.Obj(Api.Field.Person.NAME -> "Carol")),
            check = false)
          .statusCode shouldBe 401
        requests
          .delete(s"$host/htmx/asset/r/$repoId/face/${f.persistedId}", headers = headers, check = false)
          .statusCode shouldBe 401
        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
    }
  }
}
