package altitude.core.controller

import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.Histogram
import altitude.core.models.ImageQuality
import altitude.core.models.Person
import altitude.core.service.FaceDetectionService

/** The partials of Curation: the modal's shell and the fragment of a Curation set, from a Sequence or a selection */
@DoNotDiscover class CurationActionControllerTests extends ControllerTestCore {
  private val sequenceId = UUID.randomUUID().toString

  /** Measured with nothing wrong: at the luminance the rule aims for, with no clipped or crushed pixels */
  private val fine = ImageQuality(sharpness = 100, luminance = 0.45, clippedHighlights = 0, crushedShadows = 0, score = 50)

  // Identity encoding because the client cannot read a compressed empty body (the 204)
  private val identity = Map("Accept-Encoding" -> "identity")

  private def frame(quality: ImageQuality = fine): Asset = {
    val asset = testContext.persistAsset(isTriaged = true)
    testContext.setAssetImageQuality(asset.persistedId, Some(quality))
    asset
  }

  private def getSet(host: String, repoId: String, sequence: String) =
    requests.get(
      s"$host/htmx/curation/r/$repoId/sequence/$sequence",
      cookies = testContext.cookies,
      headers = identity,
      check = false)

  private def postSelection(host: String, repoId: String, assetIds: Iterable[String]) =
    requests.post(
      s"$host/htmx/curation/r/$repoId/selection",
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.Obj("assetIds" -> assetIds.mkString(",")).toString,
      cookies = testContext.cookies,
      check = false
    )

  private def frameIds(html: String): List[String] =
    """data-frame-id="([^"]+)"""".r.findAllMatchIn(html).map(_.group(1)).toList

  /** The part of the fragment from the heading `name` opens to the next section's */
  private def section(html: String, name: String): String = {
    val start = html.indexOf(s"<h3>$name")
    start should be >= 0
    html.substring(start).split("<section").head
  }

  /** The shell's state with no set that `shows` binds, up to the next block of its kind */
  private def noSetState(html: String, shows: String): String = {
    val start = html.indexOf(s"""x-show="$shows"""")
    start should be >= 0
    html.substring(start).split("curation-no-set").head
  }

  test("The shell holds the host of a set's fragment") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The shell is an HTML partial with the host each set's fragment swaps into, and no set of its own. Its too-few state says
     * so, its report state holds the report and its unread state says the set could not be read and offers Try again, each
     * offering Undo and Exit.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val response = requests.get(s"$host/htmx/curation/r/$repoId", cookies = testContext.cookies, check = false)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        response.text() should startWith("<!doctype html>")
        response.text() should include("""id="curationSet"""")
        frameIds(response.text()) shouldBe empty

        val tooFew = noSetState(response.text(), "showsTooFew")
        tooFew should include("Fewer than two images are left to compare")
        val report = noSetState(response.text(), "showsReport")
        report should include("reportText")
        val unread = noSetState(response.text(), "showsUnread")
        unread should include("This set could not be read")
        unread should include("tryAgain()")
        List(tooFew, report, unread).foreach {
          state =>
            state should include("undo()")
            state should include("exit()")
        }
    }
  }

  test("A Sequence's fragment holds its Bundles with their reasons, and its sole Contender marked") {

    /**
     * Setup:
     *
     * A Sequence of a frame with nothing wrong, a frame too dark, and a frame blurry that is also too bright. A frame of no
     * Sequence.
     *
     * Assertions:
     *
     * The fragment names each frame by its ID once, under the heading of its one Bundle or as the Contender, which is marked as
     * the only frame with no problem. Each Bundle gives its count and says what is wrong, how much and compared to what, and the
     * blurry frame's other problem shows as a chip beside it. A Bundle's header holds the switch of its details before its
     * Recycle, its reason line and its frames' reasons show by that switch, and the Contenders have none. `data-problems` names
     * what is wrong with a frame, its Bundle first and then its chips, and the Contender has none. The set is not Too close to
     * call, and it opens on the rail's first two frames: its one Contender pinned, and the first frame of its first Bundle
     * focused.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val contender = frame()
        val dark = frame(fine.copy(luminance = 0.05))
        val blurry = frame(fine.copy(sharpness = 10, clippedHighlights = 0.2))
        val lone = frame()
        testContext.setAssetSequence(List(contender, dark, blurry), sequenceId)

        val response = getSet(host, repoId, sequenceId)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        val html = response.text()
        html should startWith("<!doctype html>")

        frameIds(html).toSet shouldBe Set(contender, dark, blurry).map(_.persistedId)
        frameIds(html) should have size 3
        frameIds(html) should not contain lone.persistedId

        (html should not).include("Too close to call")
        html should include(s"""data-opening-pinned-id="${contender.persistedId}"""")
        html should include(s"""data-opening-focused-id="${frameIds(html)(1)}"""")
        frameIds(html).head shouldBe contender.persistedId

        val contenders = section(html, "Contenders")
        frameIds(contenders) shouldBe List(contender.persistedId)
        contenders should include("The only frame with no problem")

        val tooDark = section(html, "Too dark")
        tooDark should include("(1)")
        frameIds(tooDark) shouldBe List(dark.persistedId)
        tooDark should include("Luminance 5%, under 15%")
        tooDark should include("Luminance 5% against 45%")
        tooDark.indexOf("toggleDetails('TooDark')") should be >= 0
        tooDark.indexOf("toggleDetails('TooDark')") should be < tooDark.indexOf("recycleBundle('TooDark')")
        """x-show="showsDetails\('TooDark'\)"""".r.findAllIn(tooDark).size shouldBe 2
        (contenders should not).include("Details(")

        val blur = section(html, "Blurry")
        blur should include("(1)")
        frameIds(blur) shouldBe List(blurry.persistedId)
        blur should include("Sharpness 10 against 100")
        blur should include(">Too bright</span>")

        def problemsOf(frameId: String) = s"""data-frame-id="$frameId"[^>]*data-problems"""
        s"""${problemsOf(blurry.persistedId)}="Blurry,Too bright"""".r.findFirstIn(html) should not be empty
        s"""${problemsOf(dark.persistedId)}="Too dark"""".r.findFirstIn(html) should not be empty
        problemsOf(contender.persistedId).r.findFirstIn(html) shouldBe empty
    }
  }

  test("A Sequence the measurements cannot separate is Too close to call, and a problem of every frame is a notice") {

    /**
     * Setup:
     *
     * A Sequence of two frames with 8% of blown highlights each, and otherwise alike. A second Sequence with one frame the
     * pipeline did not measure beside a frame with nothing wrong.
     *
     * Assertions:
     *
     * The first set says Too close to call and "Every frame: 8% blown highlights", and both frames are Contenders under no marker
     * and no Bundle, the first pinned and the second focused when it opens. The second is Too close to call as well, and notes
     * the unmeasured frame.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val blown = List(frame(fine.copy(clippedHighlights = 0.08)), frame(fine.copy(clippedHighlights = 0.08)))
        // Every fixture is persisted before the first stamp: a random image imported after it may pair with a stamped frame
        val measured = frame()
        val unmeasured = testContext.persistAsset(isTriaged = true)
        testContext.setAssetImageQuality(unmeasured.persistedId, None)
        testContext.setAssetSequence(blown, sequenceId)

        val html = getSet(host, repoId, sequenceId).text()
        html should include("Too close to call")
        html should include("Every frame: 8% blown highlights")
        (html should not).include("The only frame with no problem")
        frameIds(section(html, "Contenders")).toSet shouldBe blown.map(_.persistedId).toSet
        html should include(s"""data-opening-pinned-id="${frameIds(html).head}"""")
        html should include(s"""data-opening-focused-id="${frameIds(html)(1)}"""")

        val other = UUID.randomUUID().toString
        testContext.setAssetSequence(List(measured, unmeasured), other)

        val noted = getSet(host, repoId, other).text()
        noted should include("Too close to call")
        noted should include("Not measured")
    }
  }

  test(
    "A set's Bundles show their frames with no toggle, its rail marks a frame in an album, and its face strip has a column per frame") {

    /**
     * Setup:
     *
     * A Sequence of four frames, one in an album, with a Person in three of them, which makes the fourth a Poor faces Bundle.
     *
     * Assertions:
     *
     * The Bundle lists its frame and nothing in the fragment collapses it. The frame in an album is marked, and no other. The
     * face strip's row holds a crop from the content route with its box in upright pixels for each of the three frames, and a
     * dash for the fourth.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val frames = List.fill(4)(frame())
        testContext.setAssetSequence(frames, sequenceId)
        val album = testApp.service.album.add("kept")
        testApp.service.album.addAssets(album.persistedId, Set(frames.head.persistedId))

        val person = testApp.service.person.addPerson(Person())
        val features = Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(0.1f)
        val faces = frames.take(3).map(testContext.addTestFace(person, _, features))

        val html = getSet(host, repoId, sequenceId).text()
        (html should not).include("data-bundles-collapse")
        (html should not).include("toggleBundle(")
        (html should not).include("isBundleOpen(")
        frameIds(section(html, "Poor faces")) shouldBe List(frames.last.persistedId)
        s"""data-frame-id="${frames.head.persistedId}"[^>]*data-in-album-or-location""".r.findFirstIn(html) should not be empty
        "data-in-album-or-location".r.findAllIn(html).size shouldBe 1

        val face = faces.head
        html should include(s"/content/r/$repoId/face/${face.persistedId}")
        html should include(s"""data-face-box="${face.x1},${face.y1},${face.width},${face.height}"""")
        "data-face-box".r.findAllIn(html).size shouldBe 3
        "no-face".r.findAllIn(html).size shouldBe 1
    }
  }

  test("Triage's results alone hold the button that opens Curation on the queue") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The results of the Triage view hold the Curate button, bound to the count store, and neither the repository view's nor the
     * trash view's do.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        def results(view: String) =
          requests.get(s"$host/htmx/search/r/$repoId", params = Map("view" -> view), cookies = testContext.cookies).text()

        results("triage") should include("""id="curateSequences"""")
        (results("repository") should not).include("curateSequences")
        (results("trashbin") should not).include("curateSequences")
    }
  }

  test("The batch ops' Curate is in both copies of the non-trash template, from two images, and carries the cap") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * The toolbar's copy (the results) and the footer's (the main page) each hold one Curate button, in the template of the views
     * outside the trash, shown from two selected images and carrying `Const.Curation.MAX_SELECTION`. The trash's template holds
     * none.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val results =
          requests.get(s"$host/htmx/search/r/$repoId", params = Map("view" -> "repository"), cookies = testContext.cookies).text()
        val mainPage = requests.get(s"$host/r/$repoId", cookies = testContext.cookies).text()

        List(results, mainPage).foreach {
          html =>
            val batchOps = html.substring(html.indexOf("""<div class="batch-ops""""))
            val trashTemplate = batchOps.indexOf("""x-if="$store.currentView.isTrashBinView()"""")
            trashTemplate should be >= 0
            val outsideTrash = batchOps.substring(0, trashTemplate)
            val inTrash = batchOps.substring(trashTemplate).split("</template>").head

            outsideTrash should include("""x-if="!$store.currentView.isTrashBinView()"""")
            val curate = outsideTrash.substring(outsideTrash.lastIndexOf("<button", outsideTrash.indexOf("batchCurateRequested")))
            curate should include("""x-show="$store.selectedAssets.size > 1"""")
            curate should include(s"""data-max-selection="${Const.Curation.MAX_SELECTION}"""")
            curate should include("Curate</button>")
            (inTrash should not).include("Curate")
        }
    }
  }

  test("A Sequence with fewer than two frames left is no content") {

    /**
     * Setup:
     *
     * A Sequence of two frames, one of them recycled.
     *
     * Assertions:
     *
     * The Sequence answers 204 with no body while one frame is left, and so does a Sequence that does not exist. Restoring the
     * frame brings its fragment back.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val frames = List(frame(), frame())
        testContext.setAssetSequence(frames, sequenceId)
        testApp.service.library.recycleAssets(Set(frames.head.persistedId))

        val response = getSet(host, repoId, sequenceId)
        response.statusCode shouldBe 204
        response.text() shouldBe empty

        getSet(host, repoId, UUID.randomUUID().toString).statusCode shouldBe 204

        testApp.service.library.restoreRecycledAssets(Set(frames.head.persistedId))
        frameIds(getSet(host, repoId, sequenceId).text()).toSet shouldBe frames.map(_.persistedId).toSet
    }
  }

  test("A Sequence over the window says how many frames it shows, and of how many") {

    /**
     * Setup:
     *
     * A Sequence of one frame over `MAX_SEQUENCE_FRAMES`, then with one of its frames recycled.
     *
     * Assertions:
     *
     * Over the window the fragment names the first `MAX_SEQUENCE_FRAMES` frames and says it shows them of all the Sequence's, and
     * that the Sequence stays in the queue. At the window it says neither.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val max = Const.Curation.MAX_SEQUENCE_FRAMES
        val frames = List.fill(max + 1)(frame())
        testContext.setAssetSequence(frames, sequenceId)

        val over = getSet(host, repoId, sequenceId).text()
        frameIds(over) should have size max
        over should include(s"Showing the first $max of ${max + 1} frames")
        over should include("The Sequence stays in the queue")

        testApp.service.library.recycleAssets(Set(frames.head.persistedId))
        val at = getSet(host, repoId, sequenceId).text()
        frameIds(at) should have size max
        (at should not).include("Showing the first")
        (at should not).include("stays in the queue")
    }
  }

  test("A Sequence ID that is not a UUID is a plain-text 400") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * An ID that is not a UUID, and one of the 40 characters that overflow the column, are each a plain-text 400 with the reason,
     * not the no content of a Sequence that does not exist.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        List("not-a-uuid", "x" * 40).foreach {
          bad =>
            val response = getSet(host, repoId, bad)
            response.statusCode shouldBe 400
            response.headers("content-type").head should include("text/plain")
            response.text() shouldBe Const.Msg.Err.VALUE_NOT_A_UUID
        }
    }
  }

  test("A selection's body that is no JSON object is a plain-text 400") {

    /**
     * Setup:
     *
     * A logged-in user's repository.
     *
     * Assertions:
     *
     * A body that is an array, a string, a number, a null or no JSON at all is each a plain-text 400 naming what is expected, as
     * for a body with no valid selection.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        List("[]", "\"text\"", "1", "null", "{", "not json").foreach {
          data =>
            withClue(s"$data: ") {
              val response = requests.post(
                s"$host/htmx/curation/r/$repoId/selection",
                headers = Map("Content-Type" -> "application/json"),
                data = data,
                cookies = testContext.cookies,
                check = false)
              response.statusCode shouldBe 400
              response.headers("content-type").head should include("text/plain")
              response.text() should include("assetIds")
            }
        }
    }
  }

  test("A selection's fragment holds the frames left of the IDs in the body") {

    /**
     * Setup:
     *
     * Three frames of no Sequence, one of them recycled.
     *
     * Assertions:
     *
     * The selection of all three, and an ID that is no asset, reads the two left. Outside a Sequence the frames' exposure is not
     * compared, so a frame darker than the other is not bundled for it.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val first = frame()
        val second = frame(fine.copy(luminance = 0.3))
        val recycled = frame()
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        val ids = List(first, second, recycled).map(_.persistedId) :+ UUID.randomUUID().toString
        val response = postSelection(host, repoId, ids)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        response.text() should startWith("<!doctype html>")
        frameIds(response.text()).toSet shouldBe Set(first.persistedId, second.persistedId)
        response.text() should include("Too close to call")
    }
  }

  test("A frame's rail item carries its Histogram and its exposure fractions, and an unmeasured frame's neither") {

    /**
     * Setup:
     *
     * A frame with its metrics and a Histogram whose tallest bin is the blue channel's last, and a frame whose metrics and
     * Histogram are cleared, as an image the measurement could not decode is left.
     *
     * Assertions:
     *
     * The measured frame's rail item carries the Histogram's relative bytes in Base64, a byte per bin on one scale, and the
     * stored fractions of its pixels crushed and blown, which the stage's strip shows. The unmeasured frame's carries none of the
     * three.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        def channel(offset: Int): IArray[Float] = IArray.tabulate(Histogram.BINS)(bin => (bin + offset) / 1000f)
        val histogram = Histogram(luminance = channel(0), red = channel(1), green = channel(2), blue = channel(3))
        val measured = frame(fine.copy(clippedHighlights = 0.142, crushedShadows = 0.005))
        testContext.setAssetHistogram(measured.persistedId, Some(histogram))
        val unmeasured = testContext.persistAsset(isTriaged = true)
        testContext.setAssetImageQuality(unmeasured.persistedId, None)
        testContext.setAssetHistogram(unmeasured.persistedId, None)

        val response = postSelection(host, repoId, List(measured, unmeasured).map(_.persistedId))
        response.statusCode shouldBe 200

        /** The opening tag of a frame's rail item */
        def railItem(asset: Asset): String =
          s"""<li class="curation-frame"[^>]*data-frame-id="${asset.persistedId}"[^>]*>""".r
            .findFirstIn(response.text())
            .getOrElse(fail(s"No rail item of $asset"))

        val relative = java.util.Base64.getEncoder.encodeToString(histogram.relativeBytes)
        railItem(measured) should include(s"""data-histogram="$relative"""")
        railItem(measured) should include("""data-crushed-shadows="0.005"""")
        railItem(measured) should include("""data-clipped-highlights="0.142"""")
        List("data-histogram", "data-crushed-shadows", "data-clipped-highlights").foreach {
          attribute => (railItem(unmeasured) should not).include(attribute)
        }
    }
  }

  test("A selection with fewer than two frames left is a fragment saying so") {

    /**
     * Setup:
     *
     * Two frames, one of them recycled.
     *
     * Assertions:
     *
     * The selection of both, of the one left, and of an ID that is no asset each answers 200 with a fragment that says too few
     * are left and names no frame.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val left = frame()
        val recycled = frame()
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        List(
          List(left.persistedId, recycled.persistedId),
          List(left.persistedId),
          List(UUID.randomUUID().toString)
        ).foreach {
          ids =>
            val response = postSelection(host, repoId, ids)
            response.statusCode shouldBe 200
            response.text() should startWith("<!doctype html>")
            response.text() should include("Fewer than two images are left to compare")
            frameIds(response.text()) shouldBe empty
        }
    }
  }

  test("A selection is capped at 100 IDs, and a body with no valid selection is refused") {

    /**
     * Setup:
     *
     * Two frames, and enough IDs that are no asset to make their selection exactly 100, then 101.
     *
     * Assertions:
     *
     * The selection of 100 reads the two frames. The selection of 101 is a plain-text 400 that names the limit.
     *
     * Edge cases:
     *
     * A repeated ID counts once, so 100 IDs and a repeat of one are still 100. An empty selection and an ID that is not a UUID
     * are plain-text 400s as well.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val frames = List(frame(), frame()).map(_.persistedId)
        val hundred = frames ++ List.fill(98)(UUID.randomUUID().toString)

        val atCap = postSelection(host, repoId, hundred)
        atCap.statusCode shouldBe 200
        frameIds(atCap.text()).toSet shouldBe frames.toSet

        postSelection(host, repoId, hundred :+ frames.head).statusCode shouldBe 200

        val overCap = postSelection(host, repoId, hundred :+ UUID.randomUUID().toString)
        overCap.statusCode shouldBe 400
        overCap.headers("content-type").head should include("text/plain")
        overCap.text() should include("100")

        List(Nil, List("not-a-uuid")).foreach {
          ids =>
            val refused = postSelection(host, repoId, ids)
            refused.statusCode shouldBe 400
            refused.headers("content-type").head should include("text/plain")
            refused.text() should not be empty
        }
    }
  }

  test("A set reads only the context repository's frames") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second user's repository holding a Sequence of two frames.
     *
     * Assertions:
     *
     * Asked of the user's own repository, the second user's Sequence is no content, and a selection of its frames says too few
     * are left.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository(user = Some(stranger))
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign =
      List.fill(2)(testContext.persistAsset(repository = Some(strangersRepo), user = Some(stranger), isTriaged = true))
    testContext.setAssetSequence(foreign, sequenceId)

    withServer(App) {
      host =>
        getSet(host, repoId, sequenceId).statusCode shouldBe 204
        val selection = postSelection(host, repoId, foreign.map(_.persistedId))
        selection.statusCode shouldBe 200
        frameIds(selection.text()) shouldBe empty
    }
  }

  test("Every route answers a repository the user does not own with a 404") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second user's repository holding a Sequence of two frames.
     *
     * Assertions:
     *
     * The shell, the Sequence's fragment and the selection's are each a 404 through the second user's repository, as for any
     * foreign entity.
     */
    testContext.persistRepository()
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository(user = Some(stranger))
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign =
      List.fill(2)(testContext.persistAsset(repository = Some(strangersRepo), user = Some(stranger), isTriaged = true))
    testContext.setAssetSequence(foreign, sequenceId)
    val repoId = strangersRepo.persistedId

    withServer(App) {
      host =>
        requests.get(s"$host/htmx/curation/r/$repoId", cookies = testContext.cookies, check = false).statusCode shouldBe 404
        getSet(host, repoId, sequenceId).statusCode shouldBe 404
        postSelection(host, repoId, foreign.map(_.persistedId)).statusCode shouldBe 404
    }
  }

  test("Every route sends a client with no session to log in") {

    /**
     * Setup:
     *
     * A repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The shell, the Sequence's fragment and the selection's each redirect to the login page, naming where the client was going.
     * Accepting JSON makes each a 401 instead.
     */
    testContext.persistRepository()
    val base = s"/htmx/curation/r/${testContext.repository.persistedId}"
    withServer(App) {
      host =>
        def responses(headers: Map[String, String]) = List(
          requests.get(s"$host$base", headers = headers, maxRedirects = 0, check = false),
          requests.get(s"$host$base/sequence/$sequenceId", headers = headers, maxRedirects = 0, check = false),
          requests.post(s"$host$base/selection", headers = headers, maxRedirects = 0, check = false)
        )

        responses(Map.empty).foreach {
          response =>
            response.statusCode shouldBe 302
            response.headers("location").head should startWith("/login?redirect=")
        }
        responses(Map("Accept" -> "application/json")).foreach(_.statusCode shouldBe 401)
    }
  }
}
