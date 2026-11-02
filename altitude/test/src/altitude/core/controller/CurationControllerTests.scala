package altitude.core.controller

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.models.Asset

/** The JSON API of Curation: the queue and its count, and a Curation set's outcome and its Undo */
@DoNotDiscover class CurationControllerTests extends ControllerTestCore {
  private def triaged(): Asset = testContext.persistAsset(isTriaged = true)

  private def ids(assets: List[Asset]): List[String] = assets.map(_.persistedId)

  private def stored(asset: Asset): Asset = testApp.service.asset.getById(asset.persistedId)

  private def post(host: String, repoId: String, action: String, payload: ujson.Value) =
    requests.post(
      s"$host/api/curation/r/$repoId/$action",
      headers = Map("Content-Type" -> "application/json"),
      data = payload.toString,
      cookies = testContext.cookies,
      check = false
    )

  private def outcome(keep: List[Asset], recycle: List[Asset]): ujson.Obj =
    ujson.Obj("keepIds" -> ids(keep), "recycleIds" -> ids(recycle))

  private def getJson(host: String, repoId: String, endpoint: String): ujson.Value = {
    val response = requests.get(s"$host/api/curation/r/$repoId/$endpoint", cookies = testContext.cookies, check = false)
    response.statusCode shouldBe 200
    response.headers("content-type").head should include("application/json")
    ujson.read(response.text())
  }

  private def queueOf(host: String, repoId: String): List[String] =
    getJson(host, repoId, "queue")("sequenceIds").arr.map(_.str).toList

  private def countOf(host: String, repoId: String): Int =
    getJson(host, repoId, "count")("count").num.toInt

  test("The queue holds the Sequence IDs in order, and the count says how many") {

    /**
     * Setup:
     *
     * A Sequence captured an hour after another and imported before it, a Sequence of one frame, and a frame of no Sequence.
     *
     * Assertions:
     *
     * The queue names the Sequence captured first, then the other, and no other, and the count says two. Each is the one field of
     * its object.
     *
     * Edge cases:
     *
     * Recycling a frame takes its Sequence out of both at once, with nothing cached, and a repository with no Sequence answers an
     * empty queue and a zero.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        queueOf(host, repoId) shouldBe empty
        countOf(host, repoId) shouldBe 0

        def sequence(taken: LocalDateTime, frames: Int = 2): (String, List[Asset]) = {
          val sequenceId = UUID.randomUUID().toString
          val assets = List.fill(frames)(triaged())
          assets.foreach(asset => testContext.setAssetDates(asset.persistedId, Some(taken), OffsetDateTime.now()))
          testContext.setAssetSequence(assets, sequenceId)
          (sequenceId, assets)
        }

        val noon = LocalDateTime.of(2024, 6, 1, 12, 0)
        val (later, laterFrames) = sequence(noon.plusHours(1))
        val (earlier, _) = sequence(noon)
        sequence(noon, frames = 1)
        triaged()

        getJson(host, repoId, "queue").obj.keySet.toSet shouldBe Set("sequenceIds")
        queueOf(host, repoId) shouldBe List(earlier, later)
        getJson(host, repoId, "count").obj.keySet.toSet shouldBe Set("count")
        countOf(host, repoId) shouldBe 2

        testApp.service.library.recycleAssets(Set(laterFrames.head.persistedId))
        queueOf(host, repoId) shouldBe List(earlier)
        countOf(host, repoId) shouldBe 1
    }
  }

  test("Resolve answers with the Sequences it cleared, which reopen takes back") {

    /**
     * Setup:
     *
     * A Sequence of three frames, in the queue.
     *
     * Assertions:
     *
     * Resolving with the first frame kept recycles the others, takes the kept frame out of the Sequence and answers with that
     * frame and its Sequence, and the queue and the count fall to none. Posting the answer back to reopen, with the same lists,
     * restores the two, answers as the restore endpoint does, and puts the frame back, so the Sequence is in the queue again.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val sequenceId = UUID.randomUUID().toString
        val frames = List.fill(3)(triaged())
        testContext.setAssetSequence(frames, sequenceId)
        val (keep, recycle) = (frames.take(1), frames.drop(1))
        queueOf(host, repoId) shouldBe List(sequenceId)

        val resolved = post(host, repoId, "resolve", outcome(keep, recycle))
        resolved.statusCode shouldBe 200
        resolved.headers("content-type").head should include("application/json")
        val answer = ujson.read(resolved.text())
        answer.obj.keySet.toSet shouldBe Set("sequenceOf")
        answer("sequenceOf").obj.toMap.view.mapValues(_.str).toMap shouldBe Map(keep.head.persistedId -> sequenceId)

        stored(keep.head).sequenceId shouldBe None
        stored(keep.head).isRecycled shouldBe false
        recycle.map(stored(_).isRecycled) shouldBe List(true, true)
        queueOf(host, repoId) shouldBe empty
        countOf(host, repoId) shouldBe 0

        val undo = outcome(keep, recycle)
        undo("sequenceOf") = answer("sequenceOf")
        val reopened = post(host, repoId, "reopen", undo)
        reopened.statusCode shouldBe 200
        reopened.headers("content-type").head should include("application/json")
        val restored = ujson.read(reopened.text())
        restored("restored").arr.map(_.str).toSet shouldBe ids(recycle).toSet
        restored("duplicates").arr shouldBe empty

        stored(keep.head).sequenceId shouldBe Some(sequenceId)
        recycle.map(stored(_).isRecycled) shouldBe List(false, false)
        queueOf(host, repoId) shouldBe List(sequenceId)
        countOf(host, repoId) shouldBe 1
    }
  }

  test("Keep all clears nothing on part of a Sequence, and answers so") {

    /**
     * Setup:
     *
     * A Sequence of three frames, two of them resolved with nothing recycled.
     *
     * Assertions:
     *
     * The answer holds no Sequence, the frames keep theirs, and the Sequence stays in the queue.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val sequenceId = UUID.randomUUID().toString
        val frames = List.fill(3)(triaged())
        testContext.setAssetSequence(frames, sequenceId)

        val response = post(host, repoId, "resolve", outcome(frames.take(2), Nil))
        response.statusCode shouldBe 200
        ujson.read(response.text())("sequenceOf").obj shouldBe empty
        frames.map(stored(_).sequenceId) shouldBe List.fill(3)(Some(sequenceId))
        queueOf(host, repoId) shouldBe List(sequenceId)
    }
  }

  test("Reopen reports a duplicate as the restore endpoint does") {

    /**
     * Setup:
     *
     * A set resolved with one frame kept and two recycled, then a second copy of the file of one of the two imported.
     *
     * Assertions:
     *
     * Reopen answers with the frame it restored and, apart from it, the one it left in the trash because its content is live
     * again.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val keep = triaged()
        val restorable = triaged()
        val dataAsset = testContext.makeAssetWithData(Some(testContext.makeAsset(isTriaged = true)))
        val secondCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))
        val duplicate: Asset = testApp.service.library.addAsset(dataAsset)
        val recycle = List(restorable, duplicate)

        post(host, repoId, "resolve", outcome(List(keep), recycle)).statusCode shouldBe 200
        testApp.service.library.addAsset(secondCopy)

        val undo = outcome(List(keep), recycle)
        undo("sequenceOf") = ujson.Obj()
        val response = post(host, repoId, "reopen", undo)
        response.statusCode shouldBe 200
        val json = ujson.read(response.text())
        json("restored").arr.map(_.str).toList shouldBe List(restorable.persistedId)
        json("duplicates").arr.map(_.str).toList shouldBe List(duplicate.persistedId)
    }
  }

  test("A refused outcome is a JSON 400 that changes nothing") {

    /**
     * Setup:
     *
     * A Sequence of three frames, the last of them recycled.
     *
     * Assertions:
     *
     * Each of these is a 400 with the reason in `error`, and the frames stay as they were: resolve and reopen with no Pick, or
     * with a Pick also to recycle; resolve with the recycled frame as a Pick; reopen putting a frame that is no Pick back in a
     * Sequence.
     *
     * Edge cases:
     *
     * A list missing, a list of no strings and a `sequenceOf` of no strings are 400s too, before anything is read.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val sequenceId = UUID.randomUUID().toString
        val frames = List.fill(3)(triaged())
        testContext.setAssetSequence(frames, sequenceId)
        val List(first, second, recycled) = frames: @unchecked
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        def undo(keep: List[Asset], recycle: List[Asset], sequenceOf: (String, ujson.Value)*): ujson.Obj = {
          val payload = outcome(keep, recycle)
          payload("sequenceOf") = ujson.Obj.from(sequenceOf)
          payload
        }

        def shouldBeRefused(action: String, payload: ujson.Value): Unit =
          withClue(s"$action $payload: ") {
            val response = post(host, repoId, action, payload)
            response.statusCode shouldBe 400
            response.headers("content-type").head should include("application/json")
            ujson.read(response.text())("error").str should not be empty
          }

        shouldBeRefused("resolve", outcome(Nil, List(first)))
        shouldBeRefused("resolve", outcome(List(first), List(first, second)))
        shouldBeRefused("resolve", outcome(List(recycled), List(first)))
        shouldBeRefused("reopen", undo(Nil, List(recycled)))
        shouldBeRefused("reopen", undo(List(recycled), List(recycled)))
        shouldBeRefused("reopen", undo(List(first), List(recycled), second.persistedId -> ujson.Str(sequenceId)))

        shouldBeRefused("resolve", ujson.Obj("keepIds" -> ids(List(first))))
        shouldBeRefused("resolve", ujson.Obj("keepIds" -> ujson.Arr(1), "recycleIds" -> ujson.Arr()))
        shouldBeRefused("reopen", outcome(List(first), List(recycled)))
        shouldBeRefused("reopen", undo(List(first), List(recycled), first.persistedId -> ujson.Num(1)))

        List(first, second).map(stored(_).isRecycled) shouldBe List(false, false)
        stored(recycled).isRecycled shouldBe true
        frames.map(stored(_).sequenceId) shouldBe List.fill(3)(Some(sequenceId))
    }
  }

  test("Reopening an ID that is no asset is a JSON 404 that changes nothing") {

    /**
     * Setup:
     *
     * A Sequence resolved with one frame kept and one recycled.
     *
     * Assertions:
     *
     * Reopen with an unknown ID among those to restore is a 404 with the reason in `error`, as for any entity not found. The
     * recycled frame stays in the trash and the kept frame out of the Sequence.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val sequenceId = UUID.randomUUID().toString
        val List(keep, recycled) = List.fill(2)(triaged()): @unchecked
        testContext.setAssetSequence(List(keep, recycled), sequenceId)
        post(host, repoId, "resolve", outcome(List(keep), List(recycled))).statusCode shouldBe 200

        val response = post(
          host,
          repoId,
          "reopen",
          ujson.Obj(
            "keepIds" -> ids(List(keep)),
            "recycleIds" -> (ids(List(recycled)) :+ UUID.randomUUID().toString),
            "sequenceOf" -> ujson.Obj(keep.persistedId -> sequenceId)
          )
        )
        response.statusCode shouldBe 404
        ujson.read(response.text())("error").str should not be empty

        stored(recycled).isRecycled shouldBe true
        stored(keep).sequenceId shouldBe None
    }
  }

  test("Every route answers a repository the user does not own with a JSON 404") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second user's repository holding a Sequence of two frames.
     *
     * Assertions:
     *
     * The queue, the count, resolve and reopen are each a JSON 404 through the second user's repository, and its frames stay as
     * they were.
     */
    testContext.persistRepository()
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository(user = Some(stranger))
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign =
      List.fill(2)(testContext.persistAsset(repository = Some(strangersRepo), user = Some(stranger), isTriaged = true))
    val sequenceId = UUID.randomUUID().toString
    testContext.setAssetSequence(foreign, sequenceId)
    val repoId = strangersRepo.persistedId
    val payload = outcome(foreign.take(1), foreign.drop(1))
    payload("sequenceOf") = ujson.Obj()

    withServer(App) {
      host =>
        val responses = List(
          requests.get(s"$host/api/curation/r/$repoId/queue", cookies = testContext.cookies, check = false),
          requests.get(s"$host/api/curation/r/$repoId/count", cookies = testContext.cookies, check = false),
          post(host, repoId, "resolve", payload),
          post(host, repoId, "reopen", payload)
        )
        responses.foreach {
          response =>
            response.statusCode shouldBe 404
            response.headers("content-type").head should include("application/json")
        }
    }

    foreign.map(stored(_).isRecycled) shouldBe List(false, false)
    foreign.map(stored(_).sequenceId) shouldBe List.fill(2)(Some(sequenceId))
  }

  test("Every route answers a client with no session with a JSON 401") {

    /**
     * Setup:
     *
     * A repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The queue, the count, resolve and reopen each answer 401 in JSON, with no redirect.
     */
    testContext.persistRepository()
    val base = s"/api/curation/r/${testContext.repository.persistedId}"
    withServer(App) {
      host =>
        List(
          requests.get(s"$host$base/queue", maxRedirects = 0, check = false),
          requests.get(s"$host$base/count", maxRedirects = 0, check = false),
          requests.post(s"$host$base/resolve", maxRedirects = 0, check = false),
          requests.post(s"$host$base/reopen", maxRedirects = 0, check = false)
        ).foreach {
          response =>
            response.statusCode shouldBe 401
            response.headers("content-type").head should include("application/json")
        }
    }
  }
}
