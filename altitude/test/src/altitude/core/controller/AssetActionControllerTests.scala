package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import scala.util.Random
import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Face
import altitude.core.models.ImageQuality
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

  private def detailPanel(host: String, repoId: String, assetId: String): requests.Response =
    requests.get(s"$host/htmx/asset/r/$repoId/detail-panel/$assetId", cookies = testContext.cookies, check = false)

  test("The Detail panel lists the asset's Faces without bad matches, Hidden marked, with its details and its links") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folders Trips and 2026 under it, an image in 2026 imported with EXIF metadata naming
     * its camera, aperture and capture time, in the album Iceland and in the Location Reykjavik under the category Europe; on it
     * a Face of Alice, a Face of the hidden Mia and a Face of a bad-match Person.
     *
     * Assertions:
     *
     * The panel answers 200 with the People section holding Alice's and Mia's rows, Mia's marked Hidden, and not the bad match's;
     * the Details with the file name, the stored dimensions, the Date Taken with its source, the folder crumbs, the album and the
     * Location each a link to its search opening in a new tab, the MIME type, the extractor's File Type and the camera rows, with
     * no Sequence row for a sorted asset, no Image quality row for a measured one and no Metadata section. Nothing marks the section read-only, and each row
     * renders its corrections: Change, Remove with its two labels, and the Change combobox's input and listbox.
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
        (html should not).include("<dt>Sequence</dt>")
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

  test("The Detail panel counts the frames of a triaged image's Sequence") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two triaged images stamped into one Sequence.
     *
     * Assertions:
     *
     * Either image's panel has a Sequence row of 2 frames and, being in triage, no Folder row.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val first = testContext.persistAsset(isTriaged = true)
        val second = testContext.persistAsset(isTriaged = true)
        testContext.setAssetSequence(List(first, second), java.util.UUID.randomUUID.toString)

        val html = detailPanel(host, repoId, first.persistedId).text()

        html should include("<dt>Sequence</dt><dd>2 frames</dd>")
        (html should not).include("<dt>Folder</dt>")
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
