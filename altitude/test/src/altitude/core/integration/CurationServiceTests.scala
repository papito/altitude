package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.ConstraintException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.jdbc.AssetDao
import altitude.core.models.Asset
import altitude.core.models.BundleKind
import altitude.core.models.CurationSet
import altitude.core.models.Person
import altitude.core.models.RestoreResult
import altitude.core.models.Settings
import altitude.core.models.Stats
import altitude.core.service.FaceDetectionService

/** The Curation queue and its count, a Sequence's or a selection's set, and a set's resolve and reopen, over `BurstFrames` */
@DoNotDiscover class CurationServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with SearchPlans
  with BurstFrames {
  import BurstFrames.*

  private def curation = testApp.service.curation

  private val MAX_FRAMES = Const.Curation.MAX_SEQUENCE_FRAMES

  /** `frames` frames of one burst a second apart, the first `startingAt` seconds after `TAKEN`, in one Sequence */
  private def burst(firstFrame: Int, startingAt: Int = 0, frames: Int = 2, model: String = MODEL): List[Asset] =
    (0 until frames).toList.map(
      offset => importFrame(frame(firstFrame + offset), taken = Some(takenAt(startingAt + offset)), model = Some(model)))

  private def sequenceOf(frames: List[Asset]): String = frames.last.sequenceId.get

  private def ids(frames: List[Asset]): Set[String] = frames.map(_.persistedId).toSet

  private def stored(asset: Asset): Asset = testApp.service.asset.getById(asset.persistedId)

  private def frameIds(set: Option[CurationSet]): List[String] = set.value.frames.map(_.asset.persistedId)

  /** The queue holds these Sequences in this order, and the count says as many */
  private def queueShouldHold(sequenceIds: String*): Unit = {
    curation.queue().map(_.sequenceId) shouldBe sequenceIds.toList
    curation.countQueue() shouldBe sequenceIds.size
  }

  private def capturedAt(seconds: Int): Option[LocalDateTime] = Some(LocalDateTime.of(2024, 6, 1, 12, 0, 0).plusSeconds(seconds))

  /** Writes one asset's row directly, for a state no service leaves it in */
  private def write(sql: String, id: String): Unit = testApp.txManager.withTransaction(update(sql, id))

  test("The queue holds the repository's Sequences, the oldest first capture first") {

    /**
     * Setup:
     *
     * A burst of two frames three minutes in imported first, then a burst of three at the start, then a lone frame in between,
     * over a minute from either.
     *
     * Assertions:
     *
     * The queue holds the burst captured first, then the other, each with its members and first capture, although the later one
     * was imported first. The lone frame has no Sequence, and the count says two.
     */
    val later = burst(0, startingAt = 180)
    val earlier = burst(2, frames = 3)
    importFrame(frame(5), taken = Some(takenAt(90))).sequenceId shouldBe None

    queueShouldHold(sequenceOf(earlier), sequenceOf(later))
    curation.queue().map(sequence => (sequence.members, sequence.firstCapturedAt)) shouldBe
      List((3, capturedAt(0)), (2, capturedAt(180)))
  }

  test("Sequences with one first capture are in the order of their IDs") {

    /**
     * Setup:
     *
     * A burst on each of two cameras, shot at the same moments.
     *
     * Assertions:
     *
     * The two are separate Sequences, and the queue holds them by ID, whichever was imported first.
     */
    val canon = sequenceOf(burst(0))
    val nikon = sequenceOf(burst(2, model = "Nikon Z8"))
    canon should not be nikon

    queueShouldHold(List(canon, nikon).sorted*)
  }

  test("A queued Sequence's cover is its earliest frame by capture time, out of the trash") {

    /**
     * Setup:
     *
     * Three frames of one burst imported out of capture order: the one captured five seconds in first, then the one captured at
     * the start, then the one captured ten seconds in. Then the frame captured at the start is recycled.
     *
     * Assertions:
     *
     * The cover is the frame captured at the start, although it was imported second. Once it is recycled, the cover is the frame
     * captured five seconds in: a recycled frame is no member, so no cover.
     */
    val middle = importFrame(frame(1), taken = Some(takenAt(5)))
    val first = importFrame(frame(0), taken = Some(takenAt(0)))
    importFrame(frame(2), taken = Some(takenAt(10)))

    curation.queue().map(_.coverId) shouldBe List(first.persistedId)

    testApp.service.library.recycleAssets(Set(first.persistedId))
    curation.queue().map(_.coverId) shouldBe List(middle.persistedId)
  }

  test("An undated Sequence's cover is its first frame imported") {

    /**
     * Setup:
     *
     * Two frames of the burst imported one by one with no metadata, under names that carry no time.
     *
     * Assertions:
     *
     * The cover is the frame imported first, whatever the IDs: with no capture time, frames go in import order.
     */
    val undated = (2 to 3).toList.map(n => importUndated(frame(n), n))

    curation.queue().map(_.coverId) shouldBe List(undated.head.persistedId)
  }

  test("A Sequence leaves the queue under two frames out of the trash, and a restore brings it back") {

    /**
     * Setup:
     *
     * A burst of three frames. The first is recycled, then the second, then both are restored.
     *
     * Assertions:
     *
     * With one frame recycled the Sequence stays, with two members and the first capture of those left. With two recycled the
     * queue is empty and the set reads as nothing. The restore brings all three back.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    queueShouldHold(sequenceId)

    testApp.service.library.recycleAssets(Set(frames.head.persistedId))
    queueShouldHold(sequenceId)
    curation.queue().map(sequence => (sequence.members, sequence.firstCapturedAt)) shouldBe List((2, capturedAt(1)))

    testApp.service.library.recycleAssets(Set(frames(1).persistedId))
    queueShouldHold()
    curation.sequenceSet(sequenceId) shouldBe None

    testApp.service.library.restoreRecycledAssets(Set(frames.head.persistedId, frames(1).persistedId))
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(3)
  }

  test("Recycling a Sequence's first frames can put it behind another") {

    /**
     * Setup:
     *
     * A burst of four frames from the start, and on another camera a burst of two a second in. The first two frames of the longer
     * one are recycled.
     *
     * Assertions:
     *
     * The longer burst is first until then and second after: a Sequence's place is the first capture of its live frames.
     */
    val longer = burst(0, frames = 4)
    val other = burst(4, startingAt = 1, model = "Nikon Z8")
    queueShouldHold(sequenceOf(longer), sequenceOf(other))

    testApp.service.library.recycleAssets(ids(longer.take(2)))
    queueShouldHold(sequenceOf(other), sequenceOf(longer))
  }

  test("A frame whose import did not complete is in no set") {

    /**
     * Setup:
     *
     * A burst of three frames and another picture, one of each marked as not completed by writing the row directly, since an
     * unfinished import carries no Sequence in practice.
     *
     * Assertions:
     *
     * The Sequence counts two members, and its set and a selection of all four leave the unfinished ones out.
     */
    val frames = burst(0, frames = 3)
    val other = importFrame(otherPicture, taken = Some(takenAt(30)))
    val unfinished = List(frames.last, other)
    unfinished.foreach(asset => write("UPDATE asset SET is_pipeline_processed = FALSE WHERE id = ?", asset.persistedId))

    curation.queue().map(_.members) shouldBe List(2)
    frameIds(curation.sequenceSet(sequenceOf(frames))) shouldBe frames.init.map(_.persistedId)
    frameIds(curation.selectionSet(ids(other :: frames))) shouldBe frames.init.map(_.persistedId)
  }

  test("Another repository's Sequence and frames are left out") {

    /**
     * Setup:
     *
     * A burst in one repository and a burst in a second, the same files.
     *
     * Assertions:
     *
     * Each repository's queue and count hold its own Sequence alone. The other repository's Sequence and frames read as nothing,
     * and are dropped from a selection beside the repository's own.
     */
    val local = burst(0)
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)
    val foreign = burst(0)
    queueShouldHold(sequenceOf(foreign))

    switchContextRepo(testContext.repositories.head)
    queueShouldHold(sequenceOf(local))
    curation.sequenceSet(sequenceOf(foreign)) shouldBe None
    curation.selectionSet(ids(foreign)) shouldBe None
    frameIds(curation.selectionSet(ids(local ++ foreign))) shouldBe local.map(_.persistedId)
  }

  test("A Sequence's set holds its frames out of the trash, in capture order") {

    /**
     * Setup:
     *
     * A burst of three frames with its middle one recycled, a burst of two with both left, and a lone frame.
     *
     * Assertions:
     *
     * The first set holds its two live frames in capture order, measured and in the Sequence, and the rule places every frame in
     * a Bundle or among the Contenders. An ID no Sequence has reads as nothing.
     */
    val frames = burst(0, frames = 3)
    burst(3, startingAt = 180)
    importFrame(frame(5), taken = Some(takenAt(90)))
    testApp.service.library.recycleAssets(Set(frames(1).persistedId))

    val set = curation.sequenceSet(sequenceOf(frames))
    frameIds(set) shouldBe List(frames.head, frames.last).map(_.persistedId)
    set.value.frames.foreach {
      read =>
        read.asset.isRecycled shouldBe false
        read.asset.sequenceId shouldBe Some(sequenceOf(frames))
        read.isMeasured shouldBe true
    }
    val placed = set.value.contenders ++ set.value.bundles.flatMap(_.frames.map(_.frame))
    placed.map(_.asset.persistedId).toSet shouldBe frameIds(set).toSet

    curation.sequenceSet("no-such-sequence") shouldBe None
  }

  test(
    "An undated Sequence is in the queue after the dated ones, its frames in import order, and its set resolves and reopens as any other") {

    /**
     * Setup:
     *
     * Three frames of the burst imported one by one with no metadata, under names that carry no time, then a dated burst of two.
     * The undated Sequence is resolved with its first frame as the Pick, then reopened with what the resolve answered.
     *
     * Assertions:
     *
     * The queue holds the dated Sequence first and the undated one, with no first capture, after it on both engines, although the
     * undated one was imported first. The undated set is in import order, whatever the IDs. The resolve takes the Pick out and
     * recycles the rest, so the Sequence leaves the queue; the reopen brings it back in the same order.
     */
    val undated = (2 to 4).toList.map(n => importUndated(frame(n), n))
    val sequenceId = sequenceOf(undated)
    val dated = burst(0)

    queueShouldHold(sequenceOf(dated), sequenceId)
    curation.queue().map(sequence => (sequence.members, sequence.firstCapturedAt)) shouldBe List((2, capturedAt(0)), (3, None))
    frameIds(curation.sequenceSet(sequenceId)) shouldBe undated.map(_.persistedId)

    val picks = ids(undated.take(1))
    val others = ids(undated.tail)
    val cleared = curation.resolve(picks, others)
    cleared shouldBe Map(undated.head.persistedId -> sequenceId)
    stored(undated.head).sequenceId shouldBe None
    stored(undated.head).isRecycled shouldBe false
    undated.tail.map(stored).foreach {
      other =>
        other.isRecycled shouldBe true
        other.sequenceId shouldBe Some(sequenceId)
    }
    queueShouldHold(sequenceOf(dated))
    curation.sequenceSet(sequenceId) shouldBe None

    curation.reopen(picks, others, cleared) shouldBe RestoreResult(restored = others, duplicates = Set.empty)
    undated.map(stored).foreach {
      undatedFrame =>
        undatedFrame.sequenceId shouldBe Some(sequenceId)
        undatedFrame.isRecycled shouldBe false
    }
    queueShouldHold(sequenceOf(dated), sequenceId)
    frameIds(curation.sequenceSet(sequenceId)) shouldBe undated.map(_.persistedId)
  }

  test("A set's frames carry their Histograms, a frame not measured has none, and another repository reads none") {

    /**
     * Setup:
     *
     * A burst of two frames run through the whole import, so each has its Histogram, and a still image inserted without the
     * pipeline, unmeasured like an image the measurement could not decode. A second repository.
     *
     * Assertions:
     *
     * The Sequence's set and a selection of the three carry each frame's stored Histogram, and the unmeasured frame has none.
     * Read from the second repository, the frames have no Histograms.
     */
    val frames = burst(0)
    val unmeasured = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    val stored = testApp.txManager.asReadOnly(testApp.DAO.asset.getHistograms(ids(unmeasured :: frames)))
    stored.keySet shouldBe ids(frames)

    def histogramsOf(set: Option[CurationSet]) =
      set.value.frames.flatMap(frame => frame.histogram.map(frame.asset.persistedId -> _)).toMap

    histogramsOf(curation.sequenceSet(sequenceOf(frames))) shouldBe stored
    val selection = curation.selectionSet(ids(unmeasured :: frames))
    frameIds(selection).toSet shouldBe ids(unmeasured :: frames)
    histogramsOf(selection) shouldBe stored

    switchContextRepo(testContext.persistRepository())
    testApp.txManager.asReadOnly(testApp.DAO.asset.getHistograms(ids(frames))) shouldBe empty
  }

  /**
   * A Sequence of `count` frames a second apart, in capture order, inserted without the pipeline since its tests read no file or
   * measurement. They are inserted last frame first, so row order says nothing about capture order.
   */
  private def longSequence(count: Int): List[Asset] = {
    val start = LocalDateTime.of(2024, 6, 1, 12, 0)
    val frames = List.fill(count)(testContext.insertAsset(testContext.makeAsset(isTriaged = true))).reverse
    frames.zipWithIndex.foreach {
      (frame, second) => testContext.setAssetDates(frame.persistedId, Some(start.plusSeconds(second)), OffsetDateTime.now())
    }
    testContext.setAssetSequence(frames, UUID.randomUUID().toString)
    frames.map(stored)
  }

  test("A Sequence over the window reads its first frames in capture order, and says how many it holds back") {

    /**
     * Setup:
     *
     * A Sequence of three frames over `MAX_SEQUENCE_FRAMES`, and in it a frame with no capture time, inserted before the others.
     *
     * Assertions:
     *
     * The set holds the first `MAX_SEQUENCE_FRAMES` in capture order and holds back four: the three past it and the undated
     * frame, which sorts last on both engines. The queue still counts all the frames.
     *
     * Edge cases:
     *
     * Recycling frames of the window brings the later ones in. At exactly `MAX_SEQUENCE_FRAMES` frames left, the window is the
     * whole Sequence, the undated frame last.
     */
    val undated = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    val frames = longSequence(MAX_FRAMES + 3)
    val sequenceId = sequenceOf(frames)
    testContext.setAssetSequence(List(undated), sequenceId)

    val set = curation.sequenceSet(sequenceId).value
    set.frames.map(_.asset.persistedId) shouldBe frames.take(MAX_FRAMES).map(_.persistedId)
    set.heldBack shouldBe 4

    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(MAX_FRAMES + 4)

    testApp.service.library.recycleAssets(ids(frames.take(4)))
    val whole = curation.sequenceSet(sequenceId).value
    whole.frames.map(_.asset.persistedId) shouldBe frames.drop(4).map(_.persistedId) :+ undated.persistedId
    whole.heldBack shouldBe 0
    curation.queue().map(_.members) shouldBe List(MAX_FRAMES)
  }

  test("Resolving a window recycles only its frames and clears nothing") {

    /**
     * Setup:
     *
     * A Sequence of three frames over `MAX_SEQUENCE_FRAMES`, read as its window, then kept whole, then resolved with its first
     * frame as the Pick.
     *
     * Assertions:
     *
     * Keep all on the window clears and changes nothing. The resolve recycles the window's other frames and clears nothing: the
     * Pick and the three frames past the window stay in the Sequence, which reads next as those four.
     */
    val frames = longSequence(MAX_FRAMES + 3)
    val sequenceId = sequenceOf(frames)
    val window = curation.sequenceSet(sequenceId).value.frames.map(_.asset)
    val pastWindow = frames.drop(MAX_FRAMES)

    curation.resolve(ids(window), Set.empty) shouldBe empty
    frames.map(stored).foreach {
      frame =>
        frame.isRecycled shouldBe false
        frame.sequenceId shouldBe Some(sequenceId)
    }
    frameIds(curation.sequenceSet(sequenceId)) shouldBe window.map(_.persistedId)

    curation.resolve(ids(window.take(1)), ids(window.drop(1))) shouldBe empty

    stored(window.head).sequenceId shouldBe Some(sequenceId)
    stored(window.head).isRecycled shouldBe false
    window.drop(1).map(stored).foreach(_.isRecycled shouldBe true)
    pastWindow.map(stored).foreach {
      frame =>
        frame.isRecycled shouldBe false
        frame.sequenceId shouldBe Some(sequenceId)
    }

    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(4)
    val left = curation.sequenceSet(sequenceId).value
    left.frames.map(_.asset.persistedId) shouldBe (window.head :: pastWindow).map(_.persistedId)
    left.heldBack shouldBe 0
  }

  test("A selection keeps its still images in the repository and out of the trash") {

    /**
     * Setup:
     *
     * Two still images, a Video, an animated image (a still with a duration written), a recycled image, and an unknown ID.
     *
     * Assertions:
     *
     * The set of all of them holds the two still images. A selection left with fewer than two, or empty, reads as nothing.
     */
    val first = testContext.insertAsset()
    val second = testContext.insertAsset()
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    val animated = testContext.insertAsset()
    write("UPDATE asset SET duration_ms = 600 WHERE id = ?", animated.persistedId)
    val recycled = testContext.insertAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val others = Set(video, animated, recycled).map(_.persistedId) + "no-such-asset"
    frameIds(curation.selectionSet(others ++ ids(List(first, second)))).toSet shouldBe ids(List(first, second))
    curation.selectionSet(others + first.persistedId) shouldBe None
    curation.selectionSet(Set.empty) shouldBe None
  }

  test("A selection may hold part of a Sequence and frames with none") {

    /**
     * Setup:
     *
     * A burst of three frames, and a lone frame. Two frames of the burst and the lone frame are selected.
     *
     * Assertions:
     *
     * The set holds the three in capture order, each with its Sequence or none, for the rule to tell what compares.
     */
    val frames = burst(0, frames = 3)
    val lone = importFrame(frame(5), taken = Some(takenAt(90)))

    val set = curation.selectionSet(Set(frames.head.persistedId, frames(1).persistedId, lone.persistedId))
    set.value.frames.map(read => read.asset.persistedId -> read.asset.sequenceId) shouldBe List(
      frames.head.persistedId -> frames.last.sequenceId,
      frames(1).persistedId -> frames.last.sequenceId,
      lone.persistedId -> None)
  }

  test("The Faces of hidden and bad-match people are left out") {

    /**
     * Setup:
     *
     * A burst of two frames with Faces of their own, each given a Face of a visible person; the first also one of a person then
     * hidden, the second one of a person then marked as a bad match.
     *
     * Assertions:
     *
     * Before the marking, each frame reads with all its Faces. After it, the set and the selection drop the hidden person's and
     * the bad match's.
     */
    val frames = burst(0)
    val features = Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(0.1f)
    val visible: Person = testApp.service.person.addPerson(Person())
    val hidden: Person = testApp.service.person.addPerson(Person())
    val badMatch: Person = testApp.service.person.addPerson(Person())
    frames.foreach(testContext.addTestFace(visible, _, features))
    testContext.addTestFace(hidden, frames.head, features)
    testContext.addTestFace(badMatch, frames.last, features)

    def peopleOnFrames(set: Option[CurationSet]): List[Set[String]] =
      set.value.frames.map {
        read =>
          read.faces.flatMap(_.assetId).distinct shouldBe List(read.asset.persistedId)
          read.faces.flatMap(_.personId).toSet
      }

    val before = peopleOnFrames(curation.sequenceSet(sequenceOf(frames)))
    (before.head should contain).allOf(visible.persistedId, hidden.persistedId)
    (before.last should contain).allOf(visible.persistedId, badMatch.persistedId)

    testApp.service.person.setVisibility(hidden, isHidden = true)
    testApp.service.person.markAsBadMatch(badMatch)

    val expected = List(before.head - hidden.persistedId, before.last - badMatch.persistedId)
    peopleOnFrames(curation.sequenceSet(sequenceOf(frames))) shouldBe expected
    peopleOnFrames(curation.selectionSet(ids(frames))) shouldBe expected
  }

  test("A frame says whether it is in any album or Location") {

    /**
     * Setup:
     *
     * A burst of four frames: one in an album, one in a Location, one in both, one in neither. The album then gives up its
     * frames.
     *
     * Assertions:
     *
     * The set and the selection read the first three as in an album or Location and the last as not. Once the album lets go, the
     * frame only it held reads as not.
     */
    val frames = burst(0, frames = 4)
    val album = testApp.service.album.add("kept")
    val location = testApp.service.location.addLocation("Paris", 48.85, 2.35)
    testApp.service.album.addAssets(album.persistedId, Set(frames.head.persistedId, frames(2).persistedId))
    testApp.service.location.addAssets(location.persistedId, Set(frames(1).persistedId, frames(2).persistedId))

    def held(set: Option[CurationSet]): List[Boolean] = set.value.frames.map(_.inAlbumOrLocation)
    held(curation.sequenceSet(sequenceOf(frames))) shouldBe List(true, true, true, false)
    held(curation.selectionSet(ids(frames))) shouldBe List(true, true, true, false)

    testApp.service.album.removeAssets(album.persistedId, Set(frames.head.persistedId, frames(2).persistedId))
    held(curation.sequenceSet(sequenceOf(frames))) shouldBe List(false, true, true, false)
  }

  test("A set is sorted by the Settings as they are when it is read") {

    /**
     * Setup:
     *
     * A burst of two frames, read at a dark-luminance limit every frame is under, and at limits none is.
     *
     * Assertions:
     *
     * The set carries the Too dark notice under the first and not under the others, with no write in between: nothing the rule
     * decides is stored.
     */
    val sequenceId = sequenceOf(burst(0))

    withSetting(Settings.CURATION_DARK_LUMINANCE, "1") {
      curation.sequenceSet(sequenceId).value.notices.map(_.kind) should contain(BundleKind.TooDark)
    }
    withSetting(Settings.CURATION_DARK_LUMINANCE, "0") {
      withSetting(Settings.CURATION_CRUSHED_FRACTION, "1") {
        curation.sequenceSet(sequenceId).value.notices.map(_.kind) should not contain BundleKind.TooDark
      }
    }
  }

  test(
    "The queue, its count, a Sequence's window and its count, and a resolve's locking read go through the Sequence index",
    Slow) {

    /**
     * Setup:
     *
     * A burst of two frames, one of them seeded into 5,000 copies with no Sequence: a library in which the frames with a Sequence
     * are few.
     *
     * Assertions:
     *
     * The query plan names `asset_sequence` for all five statements, the first four also as read under Curate own, so they cost
     * the rows that carry a Sequence, not the library. The queue's plan names it twice: the cover subquery uses it too.
     */
    val frames = burst(0)
    val dao = testApp.DAO.asset.asInstanceOf[AssetDao]
    val plans = atScale(seedCopies(frames.head.persistedId, copies = 5000, folders = 1)) {
      // Each as an account that curates everyone's frames reads it, then as one that curates its own alone
      val owner = Some(frames.head.ownerUserId)
      List(
        dao.sequencesToCurateStatement(),
        dao.sequencesToCurateStatement(owner),
        dao.countSequencesToCurateStatement(),
        dao.countSequencesToCurateStatement(owner),
        dao.sequenceFramesStatement(sequenceOf(frames), MAX_FRAMES),
        dao.sequenceFramesStatement(sequenceOf(frames), MAX_FRAMES, owner),
        dao.countSequenceFramesStatement(sequenceOf(frames)),
        dao.countSequenceFramesStatement(sequenceOf(frames), owner),
        dao.liveSequenceMembersStatement(Set(sequenceOf(frames)))
      ).map((sql, values) => lookupPlanOf(sql, values))
    }
    plans.foreach(plan => withClue(plan)(plan should include("asset_sequence")))
    // The grouped rows alone would name the index once; the cover subquery must read it too
    withClue(plans.head)("asset_sequence".r.findAllMatchIn(plans.head).size should be >= 2)
  }

  test("A resolve recycles the others, clears the Picks' Sequence and counts the stats") {

    /**
     * Setup:
     *
     * A burst of three frames, resolved with the first as the Pick and the other two to recycle.
     *
     * Assertions:
     *
     * The answer names the Pick with the Sequence it left. The Pick is live with no Sequence; the others are recycled and keep
     * it; the stats move them from triage to recycled. The Sequence leaves the queue and has no set.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)

    curation.resolve(ids(frames.take(1)), ids(frames.tail)) shouldBe Map(frames.head.persistedId -> sequenceId)

    val pick = stored(frames.head)
    pick.sequenceId shouldBe None
    pick.isRecycled shouldBe false
    frames.tail.map(stored).foreach {
      other =>
        other.isRecycled shouldBe true
        other.sequenceId shouldBe Some(sequenceId)
    }
    val stats = testApp.service.stats.getStats
    stats.getStatValue(Stats.TRIAGE_ASSETS) shouldBe 1
    stats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 2
    queueShouldHold()
    curation.sequenceSet(sequenceId) shouldBe None
  }

  test("A resolve that fails changes nothing") {

    /**
     * Setup:
     *
     * A burst of three frames, and the repository's recycled count row deleted, so the resolve fails after writing the frames.
     *
     * Assertions:
     *
     * The resolve fails on the missing stat, and every frame is still live and in the Sequence, which stays in the queue.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    testApp.txManager.withTransaction {
      update(
        "DELETE FROM stats WHERE repository_id = ? AND dimension = ?",
        testContext.repository.persistedId,
        Stats.RECYCLED_ASSETS)
    }

    val failure = the[ConstraintException] thrownBy curation.resolve(ids(frames.take(1)), ids(frames.tail))
    failure.getMessage should include(Stats.RECYCLED_ASSETS)

    frames.map(stored).foreach {
      frame =>
        frame.isRecycled shouldBe false
        frame.sequenceId shouldBe Some(sequenceId)
    }
    queueShouldHold(sequenceId)
  }

  test("A set resolved with two Picks leaves the queue") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with two Picks.
     *
     * Assertions:
     *
     * Both are named in the answer, live and with no Sequence, so the Sequence leaves the queue.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)

    curation.resolve(ids(frames.take(2)), ids(frames.drop(2))) shouldBe
      frames.take(2).map(_.persistedId -> sequenceId).toMap

    frames.take(2).map(stored).map(pick => (pick.sequenceId, pick.isRecycled)) shouldBe List((None, false), (None, false))
    queueShouldHold()
  }

  test("A selection of part of a Sequence leaves its Picks in it and the Sequence in the queue") {

    /**
     * Setup:
     *
     * A burst of four frames, of which a selection holds two: one kept and one recycled.
     *
     * Assertions:
     *
     * Nothing is cleared. The other is recycled, and the Pick keeps its Sequence, to be compared with the frames outside the
     * selection; the Sequence stays in the queue with three frames.
     */
    val frames = burst(0, frames = 4)
    val sequenceId = sequenceOf(frames)

    curation.resolve(ids(frames.take(1)), Set(frames(1).persistedId)) shouldBe empty

    stored(frames.head).sequenceId shouldBe Some(sequenceId)
    stored(frames(1)).isRecycled shouldBe true
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(3)
  }

  test("A frame already in the trash does not keep a set from holding its Sequence") {

    /**
     * Setup:
     *
     * A burst of three frames with the last recycled from the grid, then a set of the other two resolved.
     *
     * Assertions:
     *
     * The set holds every live frame, so the Pick leaves the Sequence.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    testApp.service.library.recycleAssets(Set(frames.last.persistedId))

    curation.resolve(ids(frames.take(1)), Set(frames(1).persistedId)) shouldBe Map(frames.head.persistedId -> sequenceId)

    stored(frames.head).sequenceId shouldBe None
    queueShouldHold()
  }

  test("A selection of one whole Sequence and part of another decides each alone") {

    /**
     * Setup:
     *
     * A burst of two frames and on another camera a burst of three, and a selection holding the first whole, two of the second,
     * and a lone frame, with a Pick in each Sequence and the lone frame recycled.
     *
     * Assertions:
     *
     * The first Sequence is held whole, so its Pick leaves it and it leaves the queue. The second is not, so its Pick stays and
     * it stays queued with two frames. The lone frame is recycled.
     */
    val whole = burst(0)
    val part = burst(2, frames = 3, model = "Nikon Z8")
    val lone = importFrame(frame(5), taken = Some(takenAt(90)))
    val partSequence = sequenceOf(part)

    val cleared = curation.resolve(
      keepIds = Set(whole.head.persistedId, part.head.persistedId),
      recycleIds = Set(whole.last.persistedId, part(1).persistedId, lone.persistedId))

    cleared shouldBe Map(whole.head.persistedId -> sequenceOf(whole))
    stored(whole.head).sequenceId shouldBe None
    stored(part.head).sequenceId shouldBe Some(partSequence)
    stored(lone).isRecycled shouldBe true
    queueShouldHold(partSequence)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("Keep all clears a whole Sequence, and on part of one changes nothing") {

    /**
     * Setup:
     *
     * A burst of three frames and on another camera a burst of two. All of the first but one frame are kept, then all of the
     * second.
     *
     * Assertions:
     *
     * Keep all on part of a Sequence clears and changes nothing. Keep all on a whole Sequence dismisses it: its frames carry no
     * Sequence, none is recycled, and it leaves the queue.
     */
    val part = burst(0, frames = 3)
    val whole = burst(3, startingAt = 30, model = "Nikon Z8")

    curation.resolve(ids(part.take(2)), Set.empty) shouldBe empty

    part.map(stored).foreach {
      frame =>
        frame.sequenceId shouldBe Some(sequenceOf(part))
        frame.isRecycled shouldBe false
    }
    testApp.service.stats.getStats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    queueShouldHold(sequenceOf(part), sequenceOf(whole))

    curation.resolve(ids(whole), Set.empty) shouldBe whole.map(_.persistedId -> sequenceOf(whole)).toMap

    whole.map(stored).map(frame => (frame.sequenceId, frame.isRecycled)) shouldBe List((None, false), (None, false))
    testApp.service.stats.getStats.getStatValue(Stats.RECYCLED_ASSETS) shouldBe 0
    queueShouldHold(sequenceOf(part))
  }

  /**
   * A burst of three frames and a fourth put back to before its import's last stage, which `complete` runs. Its stats were
   * already counted by the import, so `complete` leaves the count out.
   */
  private def burstWithFrameCompleting: (List[Asset], Asset, () => Unit) = {
    val frames = burst(0, frames = 3)
    val completing = stored(importFrame(frame(3), taken = Some(takenAt(3))))
    write("UPDATE asset SET is_pipeline_processed = FALSE, sequence_id = NULL WHERE id = ?", completing.persistedId)
    val complete = () => {
      testApp.service.asset.markAsCompleted(completing): Unit
      testApp.service.sequence.assign(completing): Unit
    }
    (frames, completing, complete)
  }

  test("A resolve and a recycle of frames of its set at once both commit") {

    /**
     * Setup:
     *
     * Three triaged assets: the two of the higher IDs in one Sequence, the other in none. A third transaction holds the lowest;
     * then a recycle of it and of the highest starts and waits on it; then a selection's resolve keeping the middle one and
     * recycling the lowest starts and waits; then the lowest is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither fails. The lowest and the highest are recycled, and the Pick, the one frame of its Sequence left out of the trash,
     * leaves it.
     *
     * Edge cases:
     *
     * A resolve that locks the Pick's Sequence and then what it recycles would hold the highest while waiting on the lowest,
     * which the recycle holds while it waits on the highest: a deadlock PostgreSQL ends by failing one of them.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val List(lowest, pick, highest) = List.fill(3)(testContext.persistAsset(isTriaged = true)).sortBy(_.persistedId)
    testContext.setAssetSequence(List(pick, highest), UUID.randomUUID.toString)

    whileHolding(query("SELECT id FROM asset WHERE id = ? FOR UPDATE", lowest.persistedId): Unit)(
      () => testApp.service.library.recycleAssets(ids(List(lowest, highest))),
      () => curation.resolve(ids(List(pick)), ids(List(lowest))): Unit
    )

    List(lowest, pick, highest).map(stored(_).isRecycled) shouldBe List(true, false, true)
    stored(pick).sequenceId shouldBe None
  }

  test("A reopen and a move of its frames at once both commit") {

    /**
     * Setup:
     *
     * Two triaged assets: the one of the higher ID recycled. A third transaction holds the other; then a move of both into the
     * root folder starts and waits on it; then a reopen of a set keeping the lower and having recycled the higher, putting the
     * Pick back in a Sequence, starts and waits; then the lower is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither fails. The move, first in line, sorts both out of the trash and triage; the reopen then finds nothing to restore
     * and leaves the sorted Pick out of the Sequence.
     *
     * Edge cases:
     *
     * A reopen that restores the recycled asset and then stamps the Pick would hold the higher ID while waiting on the lower,
     * which the move holds while it waits on the higher: a deadlock PostgreSQL ends by failing one of them.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val List(pick, recycled) = List.fill(2)(testContext.persistAsset(isTriaged = true)).sortBy(_.persistedId)
    testApp.service.library.recycleAssets(ids(List(recycled)))
    val rootFolderId = RequestContext.getRepository.rootFolderId
    val reopened = new java.util.concurrent.atomic.AtomicReference[Option[RestoreResult]](None)

    whileHolding(query("SELECT id FROM asset WHERE id = ? FOR UPDATE", pick.persistedId): Unit)(
      () => testApp.service.library.moveAssetsToFolder(ids(List(pick, recycled)), rootFolderId),
      () =>
        reopened.set(
          Some(curation.reopen(ids(List(pick)), ids(List(recycled)), Map(pick.persistedId -> UUID.randomUUID.toString))))
    )

    reopened.get shouldBe Some(RestoreResult(restored = Set(), duplicates = Set()))
    List(pick, recycled).map(stored).map(asset => (asset.isRecycled, asset.isTriaged, asset.sequenceId)) shouldBe
      List((false, false, None), (false, false, None))
  }

  test("A frame of the burst completing before a resolve commits is seen by it") {

    /**
     * Setup:
     *
     * A burst of three frames and a fourth completing, held open in a transaction of its own while the three are resolved with
     * one Pick.
     *
     * Assertions:
     *
     * The resolve waits for the completion and sees the fourth frame, so its set is not the whole Sequence: nothing is cleared,
     * and the Pick and the fourth frame stay queued together. Without the wait, the new frame would be alone in the Sequence.
     */
    val (frames, completing, complete) = burstWithFrameCompleting
    val sequenceId = sequenceOf(frames)

    contend(
      first = complete(),
      second = curation.resolve(ids(frames.take(1)), ids(frames.tail)) shouldBe empty
    )

    stored(frames.head).sequenceId shouldBe Some(sequenceId)
    stored(completing).sequenceId shouldBe Some(sequenceId)
    frames.tail.map(stored(_).isRecycled) shouldBe List(true, true)
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("A frame of the burst completing after a resolve commits joins what it left") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with one Pick, held open in a transaction of its own while a fourth frame completes.
     *
     * Assertions:
     *
     * The assignment waits for the resolve, then joins the recycled frames' Sequence and stamps the Pick back into it: the two
     * end up as they would had the frame come first.
     */
    val (frames, completing, complete) = burstWithFrameCompleting
    val sequenceId = sequenceOf(frames)

    contend(
      first = curation.resolve(ids(frames.take(1)), ids(frames.tail)) shouldBe Map(frames.head.persistedId -> sequenceId),
      second = complete()
    )

    stored(frames.head).sequenceId shouldBe Some(sequenceId)
    stored(completing).sequenceId shouldBe Some(sequenceId)
    frames.tail.map(stored(_).isRecycled) shouldBe List(true, true)
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("A resolve or a reopen with no Picks, overlapping lists or a Pick that is not live is refused, an unknown one not found") {

    /**
     * Setup:
     *
     * A burst of three frames with its last recycled, and a frame in another repository, offered to resolve and reopen in each
     * shape they refuse, among them a Sequence given for a frame that was no Pick.
     *
     * Assertions:
     *
     * Each is an `IllegalOperationException`, since a resolve with no live Pick would keep nothing, except a foreign or unknown
     * Pick, which is a `NotFoundException`. Nothing changes.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    val List(first, second, inTrash) = frames.map(_.persistedId): @unchecked
    testApp.service.library.recycleAssets(Set(inTrash))
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)
    val foreign = importFrame(frame(0)).persistedId
    switchContextRepo(testContext.repositories.head)

    List(
      () => curation.resolve(Set.empty, Set(first)),
      () => curation.resolve(Set.empty, Set.empty),
      () => curation.resolve(Set(first), Set(first, second)),
      () => curation.resolve(Set(inTrash), Set(first)),
      () => curation.reopen(Set.empty, Set(inTrash), Map.empty),
      () => curation.reopen(Set(first), Set(first), Map.empty),
      () => curation.reopen(Set(first), Set(inTrash), Map(second -> sequenceId))
    ).foreach(call => an[IllegalOperationException] should be thrownBy call())
    an[NotFoundException] should be thrownBy curation.resolve(Set(foreign), Set(first))
    an[NotFoundException] should be thrownBy curation.resolve(Set(first, "no-such-asset"), Set(second))

    stored(frames.head).isRecycled shouldBe false
    stored(frames(1)).isRecycled shouldBe false
    stored(frames(2)).isRecycled shouldBe true
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("A reopen brings back the frames and the Sequences a resolve cleared") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with one Pick, and on another camera a burst of two kept whole, then both reopened with
     * what the resolve answered.
     *
     * Assertions:
     *
     * Each reopen reports the frames it restored. Every frame is back, live and in its Sequence, the stats are as they were, and
     * both Sequences are queued with all their frames.
     */
    val frames = burst(0, frames = 3)
    val kept = burst(3, startingAt = 30, model = "Nikon Z8")
    val before = testApp.service.stats.getStats

    val picks = ids(frames.take(1))
    val others = ids(frames.tail)
    val cleared = curation.resolve(picks, others)
    val clearedOfKept = curation.resolve(ids(kept), Set.empty)
    queueShouldHold()

    curation.reopen(picks, others, cleared) shouldBe RestoreResult(restored = others, duplicates = Set.empty)
    curation.reopen(ids(kept), Set.empty, clearedOfKept) shouldBe RestoreResult(restored = Set.empty, duplicates = Set.empty)

    frames.map(stored).foreach {
      frame =>
        frame.sequenceId shouldBe Some(sequenceOf(frames))
        frame.isRecycled shouldBe false
    }
    kept.map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceOf(kept)))
    val after = testApp.service.stats.getStats
    after.getStatValue(Stats.TRIAGE_ASSETS) shouldBe before.getStatValue(Stats.TRIAGE_ASSETS)
    after.getStatValue(Stats.RECYCLED_ASSETS) shouldBe before.getStatValue(Stats.RECYCLED_ASSETS)
    queueShouldHold(sequenceOf(frames), sequenceOf(kept))
    curation.queue().map(_.members) shouldBe List(3, 2)
  }

  test("A reopen reports a frame whose content was imported again, and leaves it in the trash") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with one Pick, the content of a recycled frame imported again, then a reopen.
     *
     * Assertions:
     *
     * The reopen restores the other recycled frame and reports the copied one as a duplicate, left in the trash. The Pick, the
     * restored frame and the new import, which joined as a frame of the burst, share the Sequence.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    val picks = ids(frames.take(1))
    val others = ids(frames.tail)
    val cleared = curation.resolve(picks, others)

    val imported = importFrame(frame(1), taken = Some(takenAt(1)))

    curation.reopen(picks, others, cleared) shouldBe
      RestoreResult(restored = Set(frames.last.persistedId), duplicates = Set(frames(1).persistedId))

    stored(frames(1)).isRecycled shouldBe true
    stored(frames(1)).sequenceId shouldBe Some(sequenceId)
    List(frames.head, frames.last, imported).map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceId))
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(3)
  }

  test("A reopen that fails changes nothing") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with one Pick, then a reopen with an ID that is no asset among the frames to restore.
     *
     * Assertions:
     *
     * The reopen fails as a restore does: the frames stay recycled and the Pick stays out of the Sequence.
     */
    val frames = burst(0, frames = 3)
    val picks = ids(frames.take(1))
    val cleared = curation.resolve(picks, ids(frames.tail))

    a[NotFoundException] should be thrownBy
      curation.reopen(picks, ids(frames.tail) + "00000000-0000-0000-0000-000000000000", cleared)

    frames.tail.map(stored(_).isRecycled) shouldBe List(true, true)
    stored(frames.head).sequenceId shouldBe None
    queueShouldHold()
  }

  test("A Pick moved into a folder before a reopen stays sorted") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with one Pick, the Pick moved out of triage into a folder, then a reopen.
     *
     * Assertions:
     *
     * A sorted asset never carries a Sequence, so the Pick stays as it is and the reopen does not fail on it; the recycled frames
     * are back in the Sequence without it.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    val picks = ids(frames.take(1))
    val others = ids(frames.tail)
    val cleared = curation.resolve(picks, others)

    testApp.service.library.moveAssetsToFolder(picks, RequestContext.getRepository.rootFolderId)

    curation.reopen(picks, others, cleared) shouldBe RestoreResult(restored = others, duplicates = Set.empty)

    val moved = stored(frames.head)
    moved.isTriaged shouldBe false
    moved.sequenceId shouldBe None
    frames.tail.map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceId))
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("A later frame of the burst forms a new Sequence with a Pick") {

    /**
     * Setup:
     *
     * A burst of three frames resolved with its last as the Pick, then a frame 59.5 s after the Pick and so over a minute after
     * the recycled frames, outside the window of any but the Pick.
     *
     * Assertions:
     *
     * The frame and the Pick form a new Sequence, which the queue holds; only the recycled frames keep the old one.
     */
    val frames = burst(0, frames = 3)
    val sequenceId = sequenceOf(frames)
    curation.resolve(Set(frames.last.persistedId), ids(frames.init)) shouldBe Map(frames.last.persistedId -> sequenceId)
    queueShouldHold()

    val later = importFrame(frame(3), taken = Some(takenAt(61)), subSecond = Some("500"))

    later.sequenceId should not be None
    later.sequenceId should not be Some(sequenceId)
    stored(frames.last).sequenceId shouldBe later.sequenceId
    frames.init.map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceId))
    queueShouldHold(later.sequenceId.get)
    curation.queue().map(_.members) shouldBe List(2)
  }

  test("A later frame of the burst near a recycled frame brings a Pick back into its Sequence") {

    /**
     * Setup:
     *
     * A burst of two frames resolved with the first as the Pick, then a frame a second after the recycled one, within the window
     * of both.
     *
     * Assertions:
     *
     * The recycled frame still carries the Sequence, so the new frame joins it and stamps the Pick back in; the Sequence is
     * queued again with the two.
     */
    val frames = burst(0)
    val sequenceId = sequenceOf(frames)
    curation.resolve(ids(frames.take(1)), ids(frames.tail))

    val later = importFrame(frame(2), taken = Some(takenAt(2)))

    later.sequenceId shouldBe Some(sequenceId)
    stored(frames.head).sequenceId shouldBe Some(sequenceId)
    queueShouldHold(sequenceId)
    curation.queue().map(_.members) shouldBe List(2)
  }
}
