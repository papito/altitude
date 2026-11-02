package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.dao.jdbc.AssetDao
import altitude.core.models.Asset
import altitude.core.models.BundleKind
import altitude.core.models.CurationSet
import altitude.core.models.Person
import altitude.core.models.Settings
import altitude.core.service.FaceDetectionService

/** Reading a Curation set: the queue and its count, a Sequence's frames and a selection's, over the burst of `BurstFrames` */
@DoNotDiscover class CurationServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with SearchPlans
  with BurstFrames {
  import BurstFrames.*

  private def curation = testApp.service.curation

  /** `frames` frames of one burst a second apart, the first `startingAt` seconds after `TAKEN`, in one Sequence */
  private def burst(firstFrame: Int, startingAt: Int = 0, frames: Int = 2, model: String = MODEL): List[Asset] =
    (0 until frames).toList.map(
      offset => importFrame(frame(firstFrame + offset), taken = Some(takenAt(startingAt + offset)), model = Some(model)))

  private def sequenceOf(frames: List[Asset]): String = frames.last.sequenceId.get

  private def ids(frames: List[Asset]): Set[String] = frames.map(_.persistedId).toSet

  private def frameIds(set: Option[CurationSet]): List[String] = set.value.frames.map(_.asset.persistedId)

  /** The queue holds these Sequences in this order, and the count says as many */
  private def queueShouldHold(sequenceIds: String*): Unit = {
    curation.queue().map(_.sequenceId) shouldBe sequenceIds.toList
    curation.countQueue() shouldBe sequenceIds.size
  }

  private def capturedAt(seconds: Int): Option[LocalDateTime] = Some(LocalDateTime.of(2024, 6, 1, 12, 0, seconds))

  /** Writes one asset's row directly, for a state no service leaves it in */
  private def write(sql: String, id: String): Unit = testApp.txManager.withTransaction(update(sql, id))

  test("The queue holds the repository's Sequences, the oldest first capture first") {

    /**
     * Setup:
     *
     * A burst of two frames 30 seconds in imported first, then a burst of three at the start, then a lone frame in between.
     *
     * Assertions:
     *
     * The queue holds the burst captured first, then the other, each with its members and its first capture, although the later
     * one was imported first. The lone frame has no Sequence, so it is in neither, and the count says two.
     */
    val later = burst(0, startingAt = 30)
    val earlier = burst(2, frames = 3)
    importFrame(frame(5), taken = Some(takenAt(15))).sequenceId shouldBe None

    queueShouldHold(sequenceOf(earlier), sequenceOf(later))
    curation.queue().map(sequence => (sequence.members, sequence.firstCapturedAt)) shouldBe
      List((3, capturedAt(0)), (2, capturedAt(30)))
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
    burst(3, startingAt = 30)
    importFrame(frame(5), taken = Some(takenAt(15)))
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
    val lone = importFrame(frame(5), taken = Some(takenAt(15)))

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

  test("The queue, its count and a Sequence's frames are read through the Sequence index") {

    /**
     * Setup:
     *
     * A burst of two frames, and one of them seeded into 5,000 copies with no Sequence, analyzed and rolled back once the plans
     * are read: a library the planner has rows to choose by, in which the frames with a Sequence are few.
     *
     * Assertions:
     *
     * The engine's plan names `asset_sequence` for all three statements, so the toolbar's count, read after every mutation, costs
     * the rows that carry a Sequence and not the library.
     */
    val frames = burst(0)
    val dao = testApp.DAO.asset.asInstanceOf[AssetDao]
    val statements = List(
      dao.sequencesToCurateStatement,
      dao.countSequencesToCurateStatement,
      dao.sequenceFramesStatement(sequenceOf(frames))
    )

    val plans = atScale(seedCopies(frames.head.persistedId, copies = 5000, folders = 1)) {
      statements.map((sql, values) => lookupPlanOf(sql, values))
    }
    plans.foreach(plan => withClue(plan)(plan should include("asset_sequence")))
  }
}
