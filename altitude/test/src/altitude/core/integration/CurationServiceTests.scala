package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

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

/**
 * A Curation set: reading the queue and its count, a Sequence's frames and a selection's, and a set's outcome (resolve and
 * reopen), over the burst of `BurstFrames`
 */
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
     * The queue holds the burst captured first, then the other, each with its members and its first capture, although the later
     * one was imported first. The lone frame has no Sequence, so it is in neither, and the count says two.
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

  test("A Sequence leaves the queue under two frames out of the trash, and a restore brings it back") {

    /**
     * Setup:
     *
     * A burst of three frames. The first is recycled, then the second, then both are restored.
     *
     * Assertions:
     *
     * With a frame recycled the Sequence stays, with two members and the first capture of the frames left. With two recycled, one
     * frame is left: the queue is empty, the count is zero, and the set reads as nothing. The restore brings all three back.
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
     * The longer burst is first until then, and second after, since a Sequence's place is the first capture of its frames still
     * out of the trash.
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
     * A burst of three frames, and another picture, one of each marked as not completed. An import unfinished carries no Sequence
     * in practice, so the row is written directly.
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
     * Each repository's queue and count hold its own Sequence alone. The other repository's Sequence reads as nothing, its frames
     * alone are no selection, and among a repository's own frames they are dropped.
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
     * The first set holds its two frames left in capture order, each read as a frame with its measurements and its Sequence, and
     * they come back from the rule: every frame is in a Bundle or a Contender. An ID no Sequence has reads as nothing.
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

  /**
   * A Sequence of `count` frames, in capture order, a second apart. They are imported last frame first, so the order of the rows
   * says nothing about capture order.
   */
  private def longSequence(count: Int): List[Asset] = {
    val start = LocalDateTime.of(2024, 6, 1, 12, 0)
    val frames = List.fill(count)(testContext.persistAsset(isTriaged = true)).reverse
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
     * A Sequence of three frames over `MAX_SEQUENCE_FRAMES`, and in it a frame with no capture time, imported before the others.
     *
     * Assertions:
     *
     * The set holds the first `MAX_SEQUENCE_FRAMES` in capture order and holds back four: the three frames past it and the frame
     * with no capture time, which sorts last on both engines. The queue still counts all the frames.
     *
     * Edge cases:
     *
     * Recycling frames of the window brings the frames past it in. At exactly `MAX_SEQUENCE_FRAMES` frames left, the window is
     * the whole Sequence and holds nothing back, the frame with no capture time last.
     */
    val undated = testContext.persistAsset(isTriaged = true)
    testContext.setAssetDates(undated.persistedId, None, OffsetDateTime.now())
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
     * Keep all on the window answers nothing cleared and changes nothing, so the same window reads again. The resolve recycles
     * the window's other frames and answers nothing cleared. The Pick stays in the Sequence, the three frames past the window are
     * untouched, and the Sequence stays in the queue, to be read again as the Pick and those three, holding nothing back.
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
     * Two still images, a Video, an animated image (a still fixture with a duration written, which is what makes one), a recycled
     * image, and an ID that is no asset.
     *
     * Assertions:
     *
     * The set of all of them holds the two still images. A selection of one still image and everything else has fewer than two
     * left, as has an empty one, and reads as nothing.
     */
    val first = testContext.persistAsset()
    val second = testContext.persistAsset()
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    val animated = testContext.persistAsset()
    write("UPDATE asset SET duration_ms = 600 WHERE id = ?", animated.persistedId)
    val recycled = testContext.persistAsset()
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
     * The set holds the three in capture order, each read with its Sequence or none, for the rule to tell what compares.
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
     * A burst of two frames, whose picture has Faces of its own, each given a Face of a person left visible, the first a Face of
     * a person then hidden, the second a Face of a person then marked as a bad match.
     *
     * Assertions:
     *
     * Before the marking, the Sequence's set reads each frame with its own Faces and the added ones. After it, the set and the
     * selection of the two read each frame with the same Faces less the hidden person's and the bad match's.
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
     * The Sequence's set and the selection each read the first three as in an album or Location and the last as not. Once the
     * album has let go, the frame only it held reads as not, and the frame also in the Location still does.
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
     * Under the first the set carries the Too dark notice, and under the others it does not, with no write in between: nothing
     * the rule decides is stored.
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

  test("The queue, its count, a Sequence's window and its count, and a resolve's locking read go through the Sequence index") {

    /**
     * Setup:
     *
     * A burst of two frames, and one of them seeded into 5,000 copies with no Sequence, analyzed and rolled back once the plans
     * are read: a library the planner has rows to choose by, in which the frames with a Sequence are few.
     *
     * Assertions:
     *
     * The engine's plan names `asset_sequence` for all five statements, so the toolbar's count, read after every mutation, costs
     * the rows that carry a Sequence and not the library.
     */
    val frames = burst(0)
    val dao = testApp.DAO.asset.asInstanceOf[AssetDao]
    val plans = atScale(seedCopies(frames.head.persistedId, copies = 5000, folders = 1)) {
      List(
        dao.sequencesToCurateStatement,
        dao.countSequencesToCurateStatement,
        dao.sequenceFramesStatement(sequenceOf(frames), MAX_FRAMES),
        dao.countSequenceFramesStatement(sequenceOf(frames)),
        dao.liveSequenceMembersStatement(Set(sequenceOf(frames)))
      ).map((sql, values) => lookupPlanOf(sql, values))
    }
    plans.foreach(plan => withClue(plan)(plan should include("asset_sequence")))
  }

  test("A resolve recycles the others, clears the Picks' Sequence and counts the stats") {

    /**
     * Setup:
     *
     * A burst of three frames, resolved with the first as the Pick and the other two to recycle.
     *
     * Assertions:
     *
     * The answer names the Pick with the Sequence it left. The Pick is out of the Sequence and out of the trash; the others are
     * in the trash and keep it, as any recycled frame does; the stats moved them from triage to recycled. The Sequence has left
     * the queue and the count, and has no set.
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
     * A burst of three frames, and the repository's recycled count row deleted behind the services' back, so counting what a
     * resolve recycles fails once the frames have been written.
     *
     * Assertions:
     *
     * The resolve fails on the missing stat, and every frame is still out of the trash and in the Sequence, which stays in the
     * queue.
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
     * Both are named in the answer, carry no Sequence and are out of the trash, so the two frames left do not make the Sequence
     * one to compare again: it is out of the queue and the count.
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
     * Nothing is cleared, so the answer is empty. The other is recycled; the Pick keeps its Sequence, to be compared with the
     * frames outside the selection, and the Sequence stays in the queue and the count with the three frames left.
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
     * The set holds all the frames still out of the trash, so the Pick leaves the Sequence.
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
     * it stays in the queue, with the two frames left. The lone frame is recycled like any other.
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
     * Keep all on part of a Sequence answers nothing cleared: every frame is where it was, the stats are as they were and the
     * Sequence is still in the queue. Keep all on a whole Sequence dismisses it: its frames carry no Sequence, none is recycled,
     * and it leaves the queue.
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
   * A burst of three frames and a fourth frame of it about to complete: imported, then put back as it is before the last stage of
   * its import, which is what `complete` runs. Its stats were counted by the import, so that stage's count is left out.
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

  test("A frame of the burst completing before a resolve commits is seen by it") {

    /**
     * Setup:
     *
     * A burst of three frames and a fourth completing, held open in a transaction of its own while the three are resolved with
     * one Pick.
     *
     * Assertions:
     *
     * The resolve waits for the completion, then sees the fourth frame, so its set does not hold the whole Sequence: nothing is
     * cleared, and the Pick and the fourth frame are left in the Sequence, in the queue with the two of them. Read without the
     * wait, the Pick would leave the Sequence and the new frame would be alone in it, compared with nothing.
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
     * The resolve clears the Pick, and the assignment waits for it, then reads the Pick with no Sequence and the recycled frames
     * with theirs, so the fourth frame joins that Sequence with the Pick. The two end up as they would had the frame been first.
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

  test("A resolve or a reopen with no Picks, overlapping lists or a Pick that is not live is refused") {

    /**
     * Setup:
     *
     * A burst of three frames with its last recycled, and a frame in another repository. Each list a resolve refuses, then each a
     * reopen refuses: a Sequence given for a frame that was no Pick.
     *
     * Assertions:
     *
     * Each is an `IllegalOperationException`, since a resolve with no live Pick would recycle the set and keep nothing. Nothing
     * has changed: the two frames are out of the trash and in the Sequence, in the queue.
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
      () => curation.resolve(Set(foreign), Set(first)),
      () => curation.resolve(Set(first, "no-such-asset"), Set(second)),
      () => curation.reopen(Set.empty, Set(inTrash), Map.empty),
      () => curation.reopen(Set(first), Set(first), Map.empty),
      () => curation.reopen(Set(first), Set(inTrash), Map(second -> sequenceId))
    ).foreach(call => an[IllegalOperationException] should be thrownBy call())

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
     * Each reopen reports the frames it restored. Every frame is back out of the trash and in its Sequence, the stats are as they
     * were, and both Sequences are in the queue and the count with all their frames.
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
     * The reopen restores the other recycled frame and reports the copied one as a duplicate, which stays in the trash with its
     * Sequence. The Pick is in the Sequence, with the restored frame and the new import, which joined it as a frame of its burst.
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
     * A sorted asset never carries a Sequence, so the Pick stays as it is and the reopen does not fail on it: the recycled frames
     * are back in the Sequence, in the queue without the Pick.
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
     * The frame and the Pick are in a new Sequence, not the old one, which only its recycled frames carry, and the new one is
     * what the queue and the count hold.
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
     * The recycled frame still carries the Sequence, so the frame joins it and stamps the Pick into it, and the Sequence is back
     * in the queue with the two of them.
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
