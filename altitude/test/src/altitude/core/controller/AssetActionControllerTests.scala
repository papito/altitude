package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import scala.util.Random
import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.EditRecipe
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Face
import altitude.core.models.ImageQuality
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Person

/** The Face corrections of Asset detail over HTTP: the People section they answer with, and their status codes */
@DoNotDiscover class AssetActionControllerTests extends ControllerTestCore {

  private def face(person: Person, asset: Asset): Face =
    testContext.addTestFace(person, asset, Array.fill(512)(Random.nextFloat()), withFiles = true)

  private def putJson(url: String, body: ujson.Obj): requests.Response =
    requests.put(
      url,
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(body),
      cookies = testContext.cookies,
      check = false
    )

  private def changePerson(host: String, repoId: String, faceId: String, body: ujson.Obj): requests.Response =
    putJson(s"$host/htmx/asset/r/$repoId/face/$faceId/person", body)

  private def namePerson(host: String, repoId: String, faceId: String, body: ujson.Obj): requests.Response =
    putJson(s"$host/htmx/asset/r/$repoId/face/$faceId/person/name", body)

  private def mergePerson(host: String, repoId: String, faceId: String, body: ujson.Obj): requests.Response =
    putJson(s"$host/htmx/asset/r/$repoId/face/$faceId/person/merge", body)

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

  test("Naming a Face's Person answers the People section with the new name and the result message") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person with no name and a Face on each of three assets.
     *
     * Assertions:
     *
     * Naming them " Andreea " through the Face on the first asset answers 200 with the People section: the row carries the same
     * Person under the trimmed name, the root carries "Unknown N is now Andreea" and names no deleted or merged Person. The
     * Person is stored named, still counting three assets.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val unknown = testApp.service.person.addPerson(Person())
        val assets = List.fill(3)(testContext.persistAsset())
        val faces = assets.map(face(unknown, _))

        val response = namePerson(
          host,
          repoId,
          faces.head.persistedId,
          ujson.Obj(Api.Field.PERSON_ID -> unknown.persistedId, Api.Field.Person.NAME -> " Andreea "))

        response.statusCode shouldBe 200
        val html = response.text()
        html should include(rowOf(faces.head.persistedId))
        html should include(s"""data-person-id="${unknown.persistedId}"""")
        html should include("""data-person-name="Andreea"""")
        html should include(
          s"""data-app-result-message="${Const.Msg.FaceCorrection.NAMED.format(unknown.name.get, "Andreea")}"""")
        (html should not).include("data-app-deleted-person-id")
        (html should not).include("data-app-merged-person-id")
        val named = testApp.service.person.getById(unknown.persistedId)
        named.name shouldBe Some("Andreea")
        named.isNamed shouldBe true
        named.numOfFaces shouldBe 3
    }
  }

  test("Merging a Face's Person answers the People section under the destination, its root carrying both people") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person with no name and a Face on each of three assets, and Andrei, named, with a Face
     * on each of two others.
     *
     * Assertions:
     *
     * Merging the Person into Andrei through the Face on the first asset answers 200 with the People section: the row carries
     * Andrei's ID and name, and the root carries "Unknown N merged into Andrei", the merged Person's ID and Andrei's, and no
     * deleted Person. Andrei counts five assets, and every Face of the merged Person is his.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val unknown = testApp.service.person.addPerson(Person())
        val unknownAssets = List.fill(3)(testContext.persistAsset())
        val faces = unknownAssets.map(face(unknown, _))
        val andrei = testApp.service.person.addPerson(Person(name = Some("Andrei")))
        List.fill(2)(testContext.persistAsset()).foreach(face(andrei, _))

        val response = mergePerson(
          host,
          repoId,
          faces.head.persistedId,
          ujson.Obj(Api.Field.PERSON_ID -> unknown.persistedId, Api.Field.Person.DEST_PERSON_ID -> andrei.persistedId))

        response.statusCode shouldBe 200
        val html = response.text()
        html should include(rowOf(faces.head.persistedId))
        html should include(s"""data-person-id="${andrei.persistedId}"""")
        (html should not).include(s"""data-person-id="${unknown.persistedId}"""")
        html should include(
          s"""data-app-result-message="${Const.Msg.FaceCorrection.MERGED.format(unknown.name.get, "Andrei")}"""")
        html should include(s"""data-app-merged-person-id="${unknown.persistedId}"""")
        html should include(s"""data-app-merged-into-id="${andrei.persistedId}"""")
        (html should not).include("data-app-deleted-person-id")
        testApp.service.person.getById(andrei.persistedId).numOfFaces shouldBe 5
        unknownAssets
          .flatMap(asset => testApp.service.person.getAssetFaces(asset.persistedId))
          .map(_.personId.get)
          .toSet shouldBe Set(andrei.persistedId)
    }
  }

  test("A name request names the Face's Person by ID and a free name of a valid length, or is refused") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Andrei, named, and a Person with no name with a Face on an asset and another on a
     * recycled asset.
     *
     * Assertions:
     *
     * A body with no Person, a Person ID that is not a UUID, no name, a one-letter name or a reserved name answers 400; a name
     * Andrei holds, in another case, answers 400 with "Person by that name already exists". A Person who is not the Face's
     * answers 404, and the Face on the recycled asset 409. The Person keeps no name throughout.
     *
     * Edge cases:
     *
     * Once the Person is named elsewhere, naming them through the Face answers 409 with "This person has a name now", as a stale
     * section is answered.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val andrei = testApp.service.person.addPerson(Person(name = Some("Andrei")))
        face(andrei, testContext.persistAsset())
        val unknown = testApp.service.person.addPerson(Person())
        val f = face(unknown, testContext.persistAsset())
        val trashed = testContext.persistAsset()
        val inTrash = face(unknown, trashed)
        testApp.service.library.recycleAssets(Set(trashed.persistedId))

        def name(faceId: String, personId: String, personName: String): requests.Response =
          namePerson(host, repoId, faceId, ujson.Obj(Api.Field.PERSON_ID -> personId, Api.Field.Person.NAME -> personName))

        namePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.Person.NAME -> "Carol")).statusCode shouldBe 400
        name(f.persistedId, "not-a-uuid", "Carol").statusCode shouldBe 400
        namePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.PERSON_ID -> unknown.persistedId)).statusCode shouldBe 400
        name(f.persistedId, unknown.persistedId, "C").statusCode shouldBe 400
        name(f.persistedId, unknown.persistedId, "Unknown 3").statusCode shouldBe 400
        val held = name(f.persistedId, unknown.persistedId, "ANDREI")
        held.statusCode shouldBe 400
        held.text() shouldBe Const.Msg.Err.PERSON_NAME_ALREADY_EXISTS
        name(f.persistedId, andrei.persistedId, "Carol").statusCode shouldBe 404
        name(inTrash.persistedId, unknown.persistedId, "Carol").statusCode shouldBe 409
        testApp.service.person.getById(unknown.persistedId).isNamed shouldBe false

        testApp.service.person.updateName(unknown, "Bob")
        val namedSince = name(f.persistedId, unknown.persistedId, "Carol")
        namedSince.statusCode shouldBe 409
        namedSince.text() shouldBe Const.Msg.Err.FACE_PERSON_NAMED_SINCE
        testApp.service.person.getById(unknown.persistedId).name shouldBe Some("Bob")
    }
  }

  test("A merge request names the Face's Person and a live named Person of this Library, or is refused") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Andrei, named; another Person with no name; a Person with no name with a Face on an
     * asset and another on a recycled asset; and Eve, named, in a second Library of the user's.
     *
     * Assertions:
     *
     * A body with no destination, or a destination that is not a UUID, answers 400, as do a destination with no name and the
     * Face's own Person. A destination no Person has, or Eve, answers 404, as does a Person who is not the Face's; the Face on
     * the recycled asset answers 409. The Person stays live and keeps their Face throughout.
     *
     * Edge cases:
     *
     * Once the Person is named elsewhere, merging them through the Face answers 409 with "This person has a name now", as a stale
     * section is answered, and they stay live under that name.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val andrei = testApp.service.person.addPerson(Person(name = Some("Andrei")))
        face(andrei, testContext.persistAsset())
        val otherUnknown = testApp.service.person.addPerson(Person())
        face(otherUnknown, testContext.persistAsset())
        val unknown = testApp.service.person.addPerson(Person())
        val f = face(unknown, testContext.persistAsset())
        val trashed = testContext.persistAsset()
        val inTrash = face(unknown, trashed)
        testApp.service.library.recycleAssets(Set(trashed.persistedId))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        testApp.service.repository.switchContextToRepository(repo)

        def merge(faceId: String, personId: String, destId: String): requests.Response =
          mergePerson(host, repoId, faceId, ujson.Obj(Api.Field.PERSON_ID -> personId, Api.Field.Person.DEST_PERSON_ID -> destId))

        mergePerson(host, repoId, f.persistedId, ujson.Obj(Api.Field.PERSON_ID -> unknown.persistedId)).statusCode shouldBe 400
        merge(f.persistedId, unknown.persistedId, "not-a-uuid").statusCode shouldBe 400
        val nameless = merge(f.persistedId, unknown.persistedId, otherUnknown.persistedId)
        nameless.statusCode shouldBe 400
        nameless.text() shouldBe Const.Msg.Err.MERGE_INTO_NAMELESS
        merge(f.persistedId, unknown.persistedId, unknown.persistedId).statusCode shouldBe 400
        merge(f.persistedId, unknown.persistedId, java.util.UUID.randomUUID.toString).statusCode shouldBe 404
        merge(f.persistedId, unknown.persistedId, eve.persistedId).statusCode shouldBe 404
        merge(f.persistedId, otherUnknown.persistedId, andrei.persistedId).statusCode shouldBe 404
        merge(inTrash.persistedId, unknown.persistedId, andrei.persistedId).statusCode shouldBe 409

        testApp.service.person.getLiveInRepository(unknown.persistedId).persistedId shouldBe unknown.persistedId
        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(unknown.persistedId)

        testApp.service.person.updateName(unknown, "Bob")
        val namedSince = merge(f.persistedId, unknown.persistedId, andrei.persistedId)
        namedSince.statusCode shouldBe 409
        namedSince.text() shouldBe Const.Msg.Err.FACE_PERSON_NAMED_SINCE
        testApp.service.person.getLiveInRepository(unknown.persistedId).name shouldBe Some("Bob")
    }
  }

  private def detailPanel(host: String, repoId: String, assetId: String): requests.Response =
    requests.get(s"$host/htmx/asset/r/$repoId/detail-panel/$assetId", cookies = testContext.cookies, check = false)

  test("The Detail panel lists the asset's Faces without bad matches, Hidden marked, with its details and its links") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folders Trips and 2026 under it, an image in 2026 imported with EXIF metadata naming
     * its camera, aperture and capture time, in the album Iceland and in the Location Reykjavik under the category Europe; on it
     * a Face of Alice, a Face of the hidden Mia, a Face of a Person with no name who has a Face on a second image too, and a Face
     * of a bad-match Person.
     *
     * Assertions:
     *
     * The panel answers 200 with the People section holding Alice's, Mia's and the nameless Person's rows, Mia's marked Hidden,
     * and not the bad match's; Alice's and Mia's rows marked named and the nameless Person's not, each carrying its Person's
     * number of assets, their `numOfFaces` (1 for Alice, 2 for the nameless Person); the Details with the file name, the stored
     * dimensions, the Date Taken with its source, the folder crumbs, the album and the Location each a link to its search opening
     * in a new tab, the MIME type, the extractor's File Type and the camera rows, with no Curation row for a sorted asset, no
     * Image quality row for a measured one and no Metadata section. Nothing marks the section read-only, and each row renders its
     * corrections: Change, Remove with its two labels, and the Change combobox's input and listbox.
     *
     * Edge cases:
     *
     * The Location's link names its category before it.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trips = testApp.service.folder.add("Trips")
        val year = testApp.service.folder.add("2026", Some(trips.persistedId))
        val seeded = testContext
          .makeAsset(folder = Some(year))
          .copy(extractedMetadata = ExtractedMetadata(
            Map(
              "Exif IFD0" -> Map("Model" -> "Canon EOS R5"),
              "Exif SubIFD" -> Map("F-Number" -> "f/2.8", "Date/Time Original" -> "2024:07:04 08:09:10"))))
        val image: Asset = testApp.service.library.addAsset(testContext.makeAssetWithData(Some(seeded)))
        testContext.setAssetImageQuality(image.persistedId, Some(ImageQuality(100, 0.45, 0, 0, 50)))
        val iceland = testApp.service.album.add("Iceland")
        testApp.service.album.addAssets(iceland.persistedId, Set(image.persistedId))
        val europe = testApp.service.location.addCategory("Europe")
        val reykjavik = testApp.service.location.addLocation("Reykjavik", 64.1466, -21.9426, Some(europe.persistedId))
        testApp.service.location.addAssets(reykjavik.persistedId, Set(image.persistedId))

        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val mia = testApp.service.person.addPerson(Person(name = Some("Mia")))
        val badMatch = testApp.service.person.addPerson(Person())
        val alices = face(alice, image)
        val mias = face(mia, image)
        val bads = face(badMatch, image)
        val nameless = testApp.service.person.addPerson(Person())
        val namelessFace = face(nameless, image)
        face(testApp.service.person.getById(nameless.persistedId), testContext.persistAsset())
        testApp.service.person.setVisibility(mia, isHidden = true)
        testApp.service.person.markAsBadMatch(badMatch)

        val response = detailPanel(host, repoId, image.persistedId)

        response.statusCode shouldBe 200
        val html = response.text()
        html should include("""id="detailPanelPeople"""")
        html should include(rowOf(alices.persistedId))
        html should include(rowOf(mias.persistedId))
        (html should not).include(rowOf(bads.persistedId))
        html should include("""data-person-hidden="true"""")
        html should include("Hidden")

        // A row's opening tag, which carries what the Face highlights and the combobox read of its Person
        def rowTag(faceId: String): String = s"""<div ${rowOf(faceId)}[^>]*>""".r.findFirstIn(html).get
        List(alices, mias).foreach(named => rowTag(named.persistedId) should include("""data-person-named="true""""))
        (rowTag(namelessFace.persistedId) should not).include("data-person-named")
        testApp.service.person.getById(alice.persistedId).numOfFaces shouldBe 1
        testApp.service.person.getById(nameless.persistedId).numOfFaces shouldBe 2
        rowTag(alices.persistedId) should include("""data-person-asset-count="1"""")
        rowTag(namelessFace.persistedId) should include("""data-person-asset-count="2"""")
        (html should not).include("data-app-read-only")
        html should include("data-app-face-change")
        html should include("""data-app-face-remove-label-last="Remove (last click)">Remove (click twice)</button>""")
        html should include("""role="combobox"""")
        html should include("""role="listbox"""")

        html should include(image.fileName)
        html should include(s"""<span data-app-detail-dimensions>${image.width} × ${image.height} px</span>""")
        html should include("Jul 4 2024, 8:09AM")
        html should include("EXIF original")
        html should include(s"""href="/r/$repoId?folderId=${trips.persistedId}" target="_blank"""")
        html should include(s"""href="/r/$repoId?folderId=${year.persistedId}" target="_blank"""")
        html should include(s"""href="/r/$repoId?albumId=${iceland.persistedId}" target="_blank"""")
        html should include(s"""href="/r/$repoId?locationId=${reykjavik.persistedId}" target="_blank"""")
        html should include("""<span class="category">Europe</span>""")
        html should include("Canon EOS R5")
        html should include("f/2.8")
        html should include(s"<dt>MIME Type</dt><dd>${image.assetType.mime}</dd>")
        html should include("<dt>File Type</dt><dd>PNG</dd>")
        (html should not).include("<dt>Curation</dt>")
        (html should not).include("<dt>Image quality</dt>")

        (html should not).include("<h3>Metadata</h3>")
        (html should not).include("metadata-group")
    }
  }

  test(
    "The Detail panel of an asset in the trash is read-only, an asset with no Faces says so, and an unknown asset is not found") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a recycled image at the root folder holding no Face.
     *
     * Assertions:
     *
     * The panel answers 200 with the People section marked read-only and its empty line, and no Folder row for the root; a random
     * asset ID answers 404. A recycled image with a Face renders its row without Change or Remove.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val image = testContext.persistAsset()
        testApp.service.library.recycleAssets(Set(image.persistedId))

        val response = detailPanel(host, repoId, image.persistedId)

        response.statusCode shouldBe 200
        val html = response.text()
        html should include("""data-app-read-only="true"""")
        html should include("No one in this image")
        (html should not).include("<dt>Folder</dt>")

        detailPanel(host, repoId, java.util.UUID.randomUUID.toString).statusCode shouldBe 404

        val withFace = testContext.persistAsset()
        val alices = face(testApp.service.person.addPerson(Person(name = Some("Alice"))), withFace)
        testApp.service.library.recycleAssets(Set(withFace.persistedId))
        val readOnly = detailPanel(host, repoId, withFace.persistedId).text()
        readOnly should include(rowOf(alices.persistedId))
        readOnly should include("""data-app-read-only="true"""")
        (readOnly should not).include("data-app-face-change")
        (readOnly should not).include("data-app-face-remove")
    }
  }

  test("The Detail panel of another repository's asset is not found") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs holding an image with a Face of Alice.
     *
     * Assertions:
     *
     * The panel asked for through the first repository with the other repository's asset ID is a 404, as for an unknown asset,
     * and shows nothing of the asset or its people.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testContext.persistAsset(repository = Some(otherRepo))
        face(testApp.service.person.addPerson(Person(name = Some("Alice"))), foreign)
        testApp.service.repository.switchContextToRepository(repo)

        val response = detailPanel(host, repoId, foreign.persistedId)

        response.statusCode shouldBe 404
        (response.text() should not).include(foreign.fileName)
        (response.text() should not).include("Alice")
    }
  }

  test("The asset-detail modal is a 404 for an unknown asset and for another repository's, as HTML and as JSON") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs holding an imported image.
     *
     * Assertions:
     *
     * Asked through the first repository, the asset-detail modal answers 404 for an asset ID that does not exist and for the
     * other repository's image, as HTML and when asked for as JSON, and no answer carries the image's file name.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testContext.persistAsset(repository = Some(otherRepo))
        testApp.service.repository.switchContextToRepository(repo)

        for (
          assetId <- List(java.util.UUID.randomUUID.toString, foreign.persistedId);
          headers <- List(Map.empty[String, String], Map("Accept" -> "application/json"))
        ) withClue(s"[$assetId] $headers: ") {
          val response = requests.get(
            s"$host/htmx/asset/r/$repoId/modals/asset-detail/$assetId",
            headers = headers,
            cookies = testContext.cookies,
            check = false)

          response.statusCode shouldBe 404
          (response.text() should not).include(foreign.fileName)
        }
    }
  }

  test("The asset-detail JSON carries neither the importer record nor the owner of the asset") {

    /**
     * Setup:
     *
     * A logged-in User's repository holding an image imported by another User who holds a grant in it.
     *
     * Assertions:
     *
     * Asked for as JSON, the asset-detail modal answers 200 with the image's file name, and with no `original_owner` or
     * `owner_user_id` key: the importer's email, username and ID appear nowhere in it.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    val importer = testContext.persistUser()
    testContext.grant(importer, repo)
    testApp.service.user.switchContextToUser(importer)
    val asset = testContext.persistAsset(repository = Some(repo), user = Some(importer))

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/htmx/asset/r/$repoId/modals/asset-detail/${asset.persistedId}",
          headers = Map("Accept" -> "application/json"),
          cookies = testContext.cookies,
          check = false
        )

        response.statusCode shouldBe 200
        val json = ujson.read(response.text()).obj
        json("file_name").str shouldBe asset.fileName
        json.contains("original_owner") shouldBe false
        json.contains("owner_user_id") shouldBe false
        for (detail <- List(importer.email, importer.username, importer.persistedId)) {
          (response.text() should not).include(detail)
        }
    }
  }

  /** An attribute's value as Twirl escapes it, read back */
  private def htmlUnescaped(value: String): String =
    value.replace("&quot;", "\"").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

  test("The asset detail carries the asset's Edit at its head and whether the account may edit it, as JSON and in the fragment") {

    /**
     * Setup:
     *
     * A Library with an imported image with one step of its Edit (Exposure +0.4), rendered, an imported image never edited, and
     * an imported image in the trash; a User holding every Permission, and a User holding every Permission but Edit images, each
     * logged in.
     *
     * Assertions:
     *
     * Asked for as JSON by the first User, the edited image's detail carries `edit`: the head and its label, rendered at the head
     * as revision 1, edited, its size, the size of its upright original and its Edit, and `editable` true; the fragment carries
     * the same Edit as JSON and `editable` in its data attributes. The image never edited has no `edit`, in the JSON or the
     * fragment, and is editable.
     *
     * Edge cases:
     *
     * The image in the trash is not editable, and no image is for the User without Edit images.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()
    val edited = testContext.persistAsset()
    val recipe = EditRecipe.Identity.copy(exposure = 0.4)
    testApp.service.assetEdit.addStep(edited.persistedId, 0, recipe, testContext.user)
    // Rendered now, so the render the step queued cannot change the answer while the test reads it
    testApp.service.editRender.render(edited.persistedId)
    val untouched = testContext.persistAsset()
    val recycled = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val withoutEditImages = testContext.persistUser()
    testContext.grant(withoutEditImages, repo, Permissions(Permissions.all.mask & ~Permission.EditImages.mask))

    withServer(App) {
      host =>
        def detail(asset: Asset, asJson: Boolean, cookies: Map[String, java.net.HttpCookie] = testContext.cookies) = {
          val response = requests.get(
            s"$host/htmx/asset/r/$repoId/modals/asset-detail/${asset.persistedId}",
            headers = if (asJson) Map("Accept" -> "application/json") else Map.empty,
            cookies = cookies,
            check = false
          )
          response.statusCode shouldBe 200
          response.text()
        }
        def attribute(html: String, name: String): Option[String] =
          s"""data-app-image-detail-$name="([^"]*)"""".r.findFirstMatchIn(html).map(m => htmlUnescaped(m.group(1)))

        val json = ujson.read(detail(edited, asJson = true))
        json("editable").bool shouldBe true
        json("edit") shouldBe ujson.Obj(
          "head" -> 1,
          "label" -> "Exposure +0.40",
          "rendered" -> 1,
          "rev" -> 1,
          "edited" -> true,
          "width" -> edited.width,
          "height" -> edited.height,
          "uprightWidth" -> edited.width,
          "uprightHeight" -> edited.height,
          "recipe" -> recipe.toJson
        )
        val fragment = detail(edited, asJson = false)
        attribute(fragment, "editable") shouldBe Some("true")
        attribute(fragment, "edit").map(ujson.read(_)) shouldBe Some(json("edit"))

        val untouchedJson = ujson.read(detail(untouched, asJson = true)).obj
        untouchedJson.contains("edit") shouldBe false
        untouchedJson("editable").bool shouldBe true
        attribute(detail(untouched, asJson = false), "edit") shouldBe None

        ujson.read(detail(recycled, asJson = true))("editable").bool shouldBe false
        attribute(detail(recycled, asJson = false), "editable") shouldBe Some("false")

        val cookies = cookiesOf(host, withoutEditImages)
        ujson.read(detail(edited, asJson = true, cookies))("editable").bool shouldBe false
        attribute(detail(edited, asJson = false, cookies), "editable") shouldBe Some("false")
    }
  }

  test("A change request whose body is not JSON, not a JSON object or not sent as JSON is a bad request") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person's Face.
     *
     * Assertions:
     *
     * A body that does not parse, a JSON array and a JSON body sent as plain text each answer 400, and the Face keeps its person.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val owner = testApp.service.person.addPerson(Person())
        val f = face(owner, testContext.persistAsset())

        def put(body: String, contentType: String): Int =
          requests
            .put(
              s"$host/htmx/asset/r/$repoId/face/${f.persistedId}/person",
              headers = Map("Content-Type" -> contentType),
              data = body,
              cookies = testContext.cookies,
              check = false
            )
            .statusCode

        put("{not json", "application/json") shouldBe 400
        put("[]", "application/json") shouldBe 400
        put(ujson.write(ujson.Obj(Api.Field.Person.NAME -> "Carol")), "text/plain") shouldBe 400
        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
    }
  }

  test("A triaged image's panel links to the Curation set of its Sequence") {

    /**
     * Setup:
     *
     * A logged-in user's repository with three triaged images stamped into one Sequence, of which one and then a second go to the
     * trash.
     *
     * Assertions:
     *
     * With all three out of the trash, a frame's panel has a Curation row linking to Curation on that Sequence in the Triage
     * view, carrying the Sequence for a plain click, reading "View set of 3", right under the File row, no Sequence row and,
     * being in triage, no Folder row.
     *
     * Edge cases:
     *
     * A frame in the trash is not counted, and its own panel has no Curation row, since Curation would not show it. With one
     * frame left out of the trash there is nothing to curate and no row.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val first = testContext.persistAsset(isTriaged = true)
        val second = testContext.persistAsset(isTriaged = true)
        val third = testContext.persistAsset(isTriaged = true)
        val sequenceId = java.util.UUID.randomUUID.toString
        testContext.setAssetSequence(List(first, second, third), sequenceId)
        val curationRow = "<dt>Curation</dt>"

        val html = detailPanel(host, repoId, first.persistedId).text()

        html should include(curationRow)
        html should include(
          s"""<a href="/r/$repoId?view=triage&amp;curate=$sequenceId" target="_blank" data-app-curate-sequence="$sequenceId">View set of 3</a>""")
        (html.indexOf("<dt>File</dt>") < html.indexOf(curationRow)) shouldBe true
        (html.indexOf(curationRow) < html.indexOf("<dt>Date Taken</dt>")) shouldBe true
        (html should not).include("<dt>Sequence</dt>")
        (html should not).include("<dt>Folder</dt>")

        testApp.service.library.recycleAssets(Set(third.persistedId))

        detailPanel(host, repoId, first.persistedId).text() should include(">View set of 2</a>")
        (detailPanel(host, repoId, third.persistedId).text() should not).include(curationRow)

        testApp.service.library.recycleAssets(Set(second.persistedId))

        (detailPanel(host, repoId, first.persistedId).text() should not).include(curationRow)
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
     * Every correction route answers 401 without a login: Change, the name and the merge of the Face's Person, and Remove. The
     * Face keeps its person, who keeps no name.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val headers = Map("Accept" -> "application/json", "Content-Type" -> "application/json")

    withServer(App) {
      host =>
        val owner = testApp.service.person.addPerson(Person())
        val f = face(owner, testContext.persistAsset())

        val andrei = testApp.service.person.addPerson(Person(name = Some("Andrei")))
        val bodies = Map(
          "person" -> ujson.Obj(Api.Field.Person.NAME -> "Carol"),
          "person/name" -> ujson.Obj(Api.Field.PERSON_ID -> owner.persistedId, Api.Field.Person.NAME -> "Carol"),
          "person/merge" -> ujson.Obj(
            Api.Field.PERSON_ID -> owner.persistedId,
            Api.Field.Person.DEST_PERSON_ID -> andrei.persistedId)
        )
        bodies.foreach {
          case (route, body) =>
            withClue(route) {
              requests
                .put(
                  s"$host/htmx/asset/r/$repoId/face/${f.persistedId}/$route",
                  headers = headers,
                  data = ujson.write(body),
                  check = false)
                .statusCode shouldBe 401
            }
        }
        requests
          .delete(s"$host/htmx/asset/r/$repoId/face/${f.persistedId}", headers = headers, check = false)
          .statusCode shouldBe 401
        testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
        testApp.service.person.getById(owner.persistedId).isNamed shouldBe false
    }
  }
}
