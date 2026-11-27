package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow

import altitude.core.Altitude
import altitude.core.ConstraintException
import altitude.core.RequestContext
import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.models.Settings
import altitude.core.models.Stats
import altitude.core.service.SequenceService
import altitude.core.util.SearchQuery
import altitude.core.util.SequenceRule

/**
 * Sequences as the import assigns them, over the synthesized burst of `BurstFrames`. Every decoded frame gets both Scene
 * embeddings, so the hash fallback for frames without them cannot be produced here; `SequenceRuleTests` (unit) covers it.
 */
@DoNotDiscover class SequenceServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with SearchPlans
  with BurstFrames {
  import BurstFrames.*
  import SequenceServiceTests.*

  /** Pairing frames by their likeness is the suite's subject, so every import runs the Scene models */
  override protected def usesSceneModels: Boolean = true

  private def stored(asset: Asset): Asset = testApp.service.asset.getById(asset.persistedId)

  /** The similarity of two imported frames by `model`, from the embeddings the import returned */
  private def similarity(model: SceneModel, a: Asset, b: Asset): Double =
    a.sceneEmbeddings.of(model).value.similarity(b.sceneEmbeddings.of(model).value)

  /** The least similarity two frames need under `model` at the defaults */
  private def defaultThreshold(model: SceneModel): Double = testApp.settings.current.sequenceMinSimilarity(model)

  /** A threshold of `model` no two frames of the burst clear */
  private def strictThreshold(model: SceneModel): Double = model match
    case SceneModel.Dinov2 => STRICT_DINOV2_SIMILARITY
    case SceneModel.Siglip2 => STRICT_SIGLIP2_SIMILARITY

  /** Runs `f` under thresholds no two frames of the burst clear by either model, so no pair is a Sequence */
  private def withStrictThresholds[T](f: => T): T =
    withSetting(Settings.SEQUENCE_DINOV2_MIN_SIMILARITY, STRICT_DINOV2_SIMILARITY.toString) {
      withSetting(Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY, STRICT_SIGLIP2_SIMILARITY.toString)(f)
    }

  test("Two frames of a burst share a Sequence, and a frame alone has none") {

    /**
     * Setup:
     *
     * Three imports a second apart on one camera: a frame, the next frame of its burst, and a different picture at the frames'
     * size.
     *
     * Assertions:
     *
     * The first frame, alone, gets no Sequence. The second gets one, and the first is read back in it. The different picture has
     * none, and all three have their Image quality and hash.
     */
    val first = importFrame(frame(0))
    first.sequenceId shouldBe None

    val second = importFrame(frame(1), taken = Some(takenAt(1)))
    second.sequenceId should not be None
    stored(first).sequenceId shouldBe second.sequenceId
    stored(second).sequenceId shouldBe second.sequenceId

    val other = importFrame(otherPicture, taken = Some(takenAt(2)))
    stored(other).sequenceId shouldBe None

    List(first, second, other).map(stored).foreach {
      asset =>
        asset.imageQuality should not be None
        asset.perceptualHash should not be None
    }
  }

  test("A burst across midnight pairs, and so does one under a gap longer than a day") {

    /**
     * Setup:
     *
     * A frame a second before midnight and the next a second after it, so the candidate window spans two calendar days. Then,
     * under a two-day gap, a frame thirty hours earlier, so the window spans five days with the frame's day strictly inside it.
     *
     * Assertions:
     *
     * The frames around midnight share a Sequence, and the earlier frame joins it: every day of the window is read, not only its
     * first and last.
     */
    val before = importFrame(frame(0), taken = Some("2024:06:01 23:59:59"))
    val after = importFrame(frame(1), taken = Some("2024:06:02 00:00:01"))
    after.sequenceId should not be None
    stored(before).sequenceId shouldBe after.sequenceId

    val thirtyHoursEarlier = withSetting(Settings.SEQUENCE_MAX_GAP_MS, (2 * 24 * 3600 * 1000).toString) {
      importFrame(frame(2), taken = Some("2024:05:31 17:59:59"))
    }
    thirtyHoursEarlier.sequenceId shouldBe after.sequenceId
  }

  test("A candidate a concurrent move sorts is not stamped with a Sequence") {

    /**
     * Setup:
     *
     * Two frames of a burst imported unpaired under strict thresholds. Then, at the defaults, a move of the first out of triage
     * held open in its own transaction while the second is assigned.
     *
     * Assertions:
     *
     * The assignment waits on the locked candidate, and once the move commits the sorted frame is no candidate: neither frame is
     * in a Sequence, and the first is sorted.
     */
    val (first, second) = withStrictThresholds {
      (importFrame(frame(0)), importFrame(frame(4), taken = Some(takenAt(1))))
    }
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
    val rootFolderId = RequestContext.getRepository.rootFolderId

    contend(
      first = testApp.service.library.moveAssetsToFolder(Set(first.persistedId), rootFolderId),
      second = testApp.service.sequence.assign(stored(second)).sequenceId shouldBe None
    )

    stored(first).isTriaged shouldBe false
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
  }

  /** A capture time `seconds` after `TAKEN`, as `TestContext.setAssetDates` takes it */
  private def capturedAt(seconds: Int): Option[LocalDateTime] = Some(LocalDateTime.of(2024, 6, 1, 12, 0, 0).plusSeconds(seconds))

  test("An assignment and a recycle of its candidates at once both commit") {

    /**
     * Setup:
     *
     * Three frames of a burst imported unpaired under strict thresholds; of the first two, the one of the higher ID is captured
     * first and its row written first. A third transaction holds the other; then a recycle of both starts and waits on it; then,
     * at the defaults, the third frame is assigned and waits; then the frame is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither fails. Both frames are recycled, and all three share a Sequence, as frames recycled from triage keep one.
     *
     * Edge cases:
     *
     * Candidates locked in the order the statement reaches them would hold the higher ID while waiting on the lower, which the
     * recycle, locking in ID order, holds while it waits on the higher: a deadlock PostgreSQL ends by failing one of them.
     */
    assume(testApp.dataSourceType == altitude.core.Const.DbEngineName.POSTGRES)
    val (frames, next) = withStrictThresholds {
      (
        List(importFrame(frame(0)), importFrame(frame(1), taken = Some(takenAt(1)))),
        importFrame(frame(2), taken = Some(takenAt(2))))
    }
    val List(lower, higher) = frames.sortBy(_.persistedId)
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    testContext.setAssetDates(higher.persistedId, capturedAt(0), now)
    testContext.setAssetDates(lower.persistedId, capturedAt(1), now)

    whileHolding(query("SELECT id FROM asset WHERE id = ? FOR UPDATE", lower.persistedId): Unit)(
      () => testApp.service.library.recycleAssets(frames.map(_.persistedId).toSet),
      () => testApp.service.sequence.assign(stored(next)): Unit
    )

    frames.map(stored(_).isRecycled) shouldBe List(true, true)
    val sequenceId = stored(next).sequenceId
    sequenceId should not be None
    frames.map(stored(_).sequenceId) shouldBe List(sequenceId, sequenceId)
  }

  test("A merge and a recycle of a member outside the frame's window at once both commit") {

    /**
     * Setup:
     *
     * Two frames of a burst, each in a Sequence of its own: the one of the lower ID with two more assets, the other with one
     * more, of a lower ID than its own and captured nowhere near. A third transaction holds that member; then a recycle of it and
     * its frame starts and waits on it; then a third frame of the burst is assigned, matching both frames, and waits; then the
     * member is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither fails. The member and its frame are recycled, and every asset of both Sequences is in the larger one, with the
     * third frame.
     *
     * Edge cases:
     *
     * A merge that locks its candidates and then repoints the members of the smaller Sequence locks the member outside the window
     * last, after its frame of a higher ID, which the recycle waits on holding the member: a deadlock.
     */
    assume(testApp.dataSourceType == altitude.core.Const.DbEngineName.POSTGRES)
    val (frames, next) = withStrictThresholds {
      (
        List(importFrame(frame(0)), importFrame(frame(1), taken = Some(takenAt(1)))),
        importFrame(frame(2), taken = Some(takenAt(2))))
    }
    val List(inLarger, inSmaller) = frames.sortBy(_.persistedId)
    // Of a lower ID than its frame, so the recycle locks it first
    val outside = Iterator
      .continually(testContext.persistAsset(isTriaged = true))
      .take(40)
      .find(_.persistedId < inSmaller.persistedId)
      .getOrElse(fail("No asset of a lower ID than the frame's in 40"))
    val (larger, smaller) = (UUID.randomUUID.toString, UUID.randomUUID.toString)
    testContext.setAssetSequence(inLarger :: List.fill(2)(testContext.persistAsset(isTriaged = true)), larger)
    testContext.setAssetSequence(List(inSmaller, outside), smaller)

    whileHolding(query("SELECT id FROM asset WHERE id = ? FOR UPDATE", outside.persistedId): Unit)(
      () => testApp.service.library.recycleAssets(Set(outside.persistedId, inSmaller.persistedId)),
      () => testApp.service.sequence.assign(stored(next)): Unit
    )

    List(outside, inSmaller).map(stored(_).isRecycled) shouldBe List(true, true)
    stored(next).sequenceId shouldBe Some(larger)
    testApp.txManager.asReadOnly(query("SELECT id FROM asset WHERE sequence_id = ?", smaller)) shouldBe empty
    testApp.txManager.asReadOnly(query("SELECT id FROM asset WHERE sequence_id = ?", larger)).size shouldBe 6
  }

  test("A completing frame and a rename of its importer at once both commit") {

    /**
     * Setup:
     *
     * A User who imported two frames of a burst, unpaired under strict thresholds; the one of the higher ID is put back to before
     * its completion. A third transaction holds the repository's turn among Sequence writers; then the frame's completion starts,
     * at the defaults, and waits on it; then an Admin's rename of the User, which rewrites the importer records of both frames,
     * starts and waits; then the turn is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither fails. Both frames are in one Sequence, and both importer records name the User as renamed.
     *
     * Edge cases:
     *
     * A completion that locks its own row before its importer's account holds that row while the rename, holding the account and
     * the other frame, waits on it; the completion then waits on the other frame to stamp it: a deadlock PostgreSQL ends by
     * failing one of them.
     */
    assume(testApp.dataSourceType == altitude.core.Const.DbEngineName.POSTGRES)
    val users = testApp.service.user
    val admin = testContext.persistAdmin()
    val grants = Map(testContext.repository.persistedId -> altitude.core.models.Permissions.all)
    val importer = users.create(admin.persistedId, testContext.makeUser(), "password 1", grants)
    val actor = RequestContext.account.value.get
    switchContextUser(importer)
    val frames = withStrictThresholds(List(importFrame(frame(0)), importFrame(frame(1), taken = Some(takenAt(1)))))
    switchContextUser(actor)
    val List(earlier, completing) = frames.sortBy(_.persistedId)
    testApp.txManager.withTransaction(
      update("UPDATE asset SET is_pipeline_processed = FALSE WHERE id = ?", completing.persistedId))
    val renamed = importer.copy(name = s"Renamed ${altitude.core.util.Util.randomStr(6)}")
    val opened = users.openForEdit(importer.persistedId).state

    whileHolding(testApp.DAO.asset.lockSequences())(
      () => testApp.service.library.completeImport(stored(completing)): Unit,
      () => users.update(admin.persistedId, renamed, grants, opened): Unit
    )

    val sequenceId = stored(completing).sequenceId
    sequenceId should not be None
    stored(earlier).sequenceId shouldBe sequenceId
    frames.map(stored(_).originalOwner.name) shouldBe List(renamed.name, renamed.name)
  }

  test("The candidates are read through the triage capture-day index", Slow) {

    /**
     * Setup:
     *
     * A frame seeded into 5,000 copies captured a minute apart over days, one in ten in triage, and the candidates statement for
     * a frame a second after the original.
     *
     * Assertions:
     *
     * The plan reads `asset_triage_date_taken`, which leads with the window's days and times and, being partial over the triage
     * set, is the smaller of the two capture-day indexes that otherwise tie by shape.
     */
    val stamped = stored(importFrame(frame(0)))
    val next = stamped.copy(originalCreatedAt = stamped.originalCreatedAt.map(_.plusSeconds(1)))
    val (sql, values) =
      testApp.DAO.asset.asInstanceOf[altitude.core.dao.jdbc.AssetDao].sequenceCandidatesStatement(next, 60000).get

    val plan = atScale {
      seedCopies(stamped.persistedId, copies = 5000, folders = 1)
      // Capture times from the import times, and a hash, make the copies candidates; nine in ten are sorted
      update("UPDATE asset SET original_created_at = created_at, perceptual_hash = 1 WHERE id <> ?", stamped.persistedId)
      update("UPDATE asset SET is_triaged = FALSE WHERE id <> ? AND substr(id, 36, 1) <> '0'", stamped.persistedId)
    }(lookupPlanOf(sql, values))
    withClue(plan)(plan should include("asset_triage_date_taken"))
  }

  test("The undated candidates are walked backwards through the undated triage index", Slow) {

    /**
     * Setup:
     *
     * An undated frame seeded into 5,000 copies imported a minute apart, one in ten in triage, and the undated candidates
     * statement with a limit under the triage rows of the frame's size, as the cap is when it matters.
     *
     * Assertions:
     *
     * The plan reads `asset_triage_undated` and sorts nothing: the index holds the undated triage rows by size in import order,
     * so the engine walks it backwards and stops at the limit. With a limit over those rows, PostgreSQL may instead bitmap-read
     * the same run and sort it, which costs no more.
     */
    val undated = stored(importUndated(frame(0), 1))
    undated.originalCreatedAt shouldBe None
    val (sql, values) =
      testApp.DAO.asset.asInstanceOf[altitude.core.dao.jdbc.AssetDao].undatedSequenceCandidatesStatement(undated, 100)

    val plan = atScale {
      seedCopies(undated.persistedId, copies = 5000, folders = 1)
      // A hash and no capture time make the copies undated candidates; nine in ten are sorted
      update("UPDATE asset SET perceptual_hash = 1 WHERE id <> ?", undated.persistedId)
      update("UPDATE asset SET is_triaged = FALSE WHERE id <> ? AND substr(id, 36, 1) <> '0'", undated.persistedId)
    }(lookupPlanOf(sql, values))
    withClue(plan) {
      plan should include("asset_triage_undated")
      (plan should not).include(if (isPostgres) "Sort" else "TEMP B-TREE")
    }
  }

  test("The undated read returns only the matches and counts the rows it scanned") {

    /**
     * Setup:
     *
     * Undated imports under strict thresholds, so the import pairs none and the read is tested alone: the different picture at
     * the frames' size, a half-size frame, two burst frames, and a new burst frame. The new frame's undated matches are read
     * through the DAO under the default rule, with caps of 100 and 1 and with a test that refuses everything; then a dated burst
     * frame is imported and the read repeated.
     *
     * Assertions:
     *
     * No undated import has a capture time or a Sequence. The read keeps the two burst frames, newest first, and scans three
     * rows: those two and the different picture, which share the new frame's size. Capped at 1 it keeps and scans one row;
     * refusing everything, it keeps none and still scans three.
     *
     * Edge cases:
     *
     * The half-size frame, the new frame itself and the dated frame are never scanned.
     */
    val (other, halfSize, first, second, newFrame) = withStrictThresholds {
      (
        importUndated(otherPicture, 1),
        importUndated(frame(2, scale = 0.5), 2),
        importUndated(frame(0), 3),
        importUndated(frame(1), 4),
        importUndated(frame(3), 5))
    }
    List(other, halfSize, first, second, newFrame).map(stored).foreach {
      asset =>
        asset.originalCreatedAt shouldBe None
        asset.sequenceId shouldBe None
    }
    val settings = testApp.settings.current

    def read(limit: Int, keep: Asset => Boolean = SequenceRule.matches(newFrame, _, settings)) =
      testApp.txManager.withTransaction(testApp.DAO.asset.getUndatedSequenceMatches(newFrame, limit)(keep))

    val matches = read(limit = 100)
    matches.kept.map(_.persistedId) shouldBe List(second.persistedId, first.persistedId)
    matches.scanned shouldBe 3

    val capped = read(limit = 1)
    capped.kept.map(_.persistedId) shouldBe List(second.persistedId)
    capped.scanned shouldBe 1

    val none = read(limit = 100, keep = _ => false)
    none.kept shouldBe empty
    none.scanned shouldBe 3

    importFrame(frame(4))
    read(limit = 100).scanned shouldBe 3
  }

  test("Undated frames of one scene share a Sequence, and a dated frame of it joins neither") {

    /**
     * Setup:
     *
     * Undated imports: a frame, the next frame of its burst, the different picture at the frames' size and a half-size frame.
     * Then a burst frame with the camera's clock and model.
     *
     * Assertions:
     *
     * The first frame, alone, gets no Sequence; the second gets one, and the first is read back in it. Neither has a capture
     * time, and both have their Image quality and hash. The different picture, the half-size frame and the dated frame have no
     * Sequence: undated frames pair by likeness among undated frames of their size, never with a dated one.
     */
    val first = importUndated(frame(0), 1)
    first.sequenceId shouldBe None

    val second = importUndated(frame(1), 2)
    second.sequenceId should not be None
    stored(first).sequenceId shouldBe second.sequenceId
    stored(second).sequenceId shouldBe second.sequenceId
    List(first, second).map(stored).foreach {
      undated =>
        undated.originalCreatedAt shouldBe None
        undated.imageQuality should not be None
        undated.perceptualHash should not be None
    }

    val other = importUndated(otherPicture, 3)
    val halfSize = importUndated(frame(2, scale = 0.5), 4)
    val dated = importFrame(frame(2))
    stored(dated).originalCreatedAtSource shouldBe Some(CaptureDateSource.ExifOriginal)
    List(other, halfSize, dated).map(stored(_).sequenceId).distinct shouldBe List(None)
    List(first, second).map(stored(_).sequenceId).distinct shouldBe List(second.sequenceId)
  }

  test("A service built with a cap of one compares the newest undated frame only") {

    /**
     * Setup:
     *
     * Three undated burst frames imported unpaired under strict thresholds. Then, at the defaults, the third assigned again
     * through a service with a cap of one candidate.
     *
     * Assertions:
     *
     * The third frame shares a Sequence with the second, the only frame the cap admits; the first stays out.
     */
    val (first, second, third) = withStrictThresholds {
      (importUndated(frame(0), 1), importUndated(frame(1), 2), importUndated(frame(2), 3))
    }
    List(first, second, third).map(stored(_).sequenceId).distinct shouldBe List(None)

    val assigned = new SequenceService(testApp, undatedCandidates = 1).assign(third)
    assigned.sequenceId should not be None
    List(second, third).map(stored(_).sequenceId).distinct shouldBe List(assigned.sequenceId)
    stored(first).sequenceId shouldBe None
  }

  test("A concurrent move of an undated candidate leaves it out of the Sequence") {

    /**
     * Setup:
     *
     * Two undated burst frames imported unpaired under strict thresholds. Then, at the defaults, a move of the first out of
     * triage held open in its own transaction while the second is assigned.
     *
     * Assertions:
     *
     * Neither frame ends in a Sequence, and the first is sorted. On PostgreSQL the unlocked undated read finds the first frame a
     * match before the move commits, and the locked re-read waits on it and then leaves it out, so the second is not stamped
     * alone. On SQLite the assignment waits for the one write connection and finds no candidate.
     */
    val (first, second) = withStrictThresholds {
      (importUndated(frame(0), 1), importUndated(frame(4), 2))
    }
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
    val rootFolderId = RequestContext.getRepository.rootFolderId

    contend(
      first = testApp.service.library.moveAssetsToFolder(Set(first.persistedId), rootFolderId),
      second = testApp.service.sequence.assign(second).sequenceId shouldBe None
    )

    stored(first).isTriaged shouldBe false
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
  }

  test("A frame that bridges two Sequences merges them into the larger") {

    /**
     * Setup:
     *
     * A burst of three frames at 0, 0.5 and 1 s and a burst of two at 90 and 90.5 s, too far apart to pair. Then a frame at 45.5
     * s, within the gap of both.
     *
     * Assertions:
     *
     * Before the bridge there are two Sequences, of three and two. After it all six frames are in the larger one, and nothing
     * carries the other.
     */
    val a1 = importFrame(frame(0))
    val a2 = importFrame(frame(1), subSecond = Some("500"))
    val a3 = importFrame(frame(2), taken = Some(takenAt(1)))
    val b1 = importFrame(frame(3), taken = Some(takenAt(90)))
    val b2 = importFrame(frame(4), taken = Some(takenAt(90)), subSecond = Some("500"))

    val sequenceA = stored(a1).sequenceId.get
    val sequenceB = stored(b1).sequenceId.get
    sequenceA should not be sequenceB
    List(a2, a3).map(stored(_).sequenceId) shouldBe List(Some(sequenceA), Some(sequenceA))
    stored(b2).sequenceId shouldBe Some(sequenceB)

    val bridge = importFrame(frame(5), taken = Some(takenAt(45)), subSecond = Some("500"))
    bridge.sequenceId shouldBe Some(sequenceA)
    List(a1, a2, a3, b1, b2, bridge).map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceA))
  }

  test("A merge of Sequences of one size keeps the one captured first") {

    /**
     * Setup:
     *
     * A burst of two frames at 90 and 91 s imported first, then a burst at 0 and 1 s, then a frame at 45.5 s that bridges them.
     *
     * Assertions:
     *
     * All five end in the Sequence of the burst captured first, although the other was made first.
     */
    val later1 = importFrame(frame(0), taken = Some(takenAt(90)))
    val later2 = importFrame(frame(1), taken = Some(takenAt(91)))
    val earlier1 = importFrame(frame(2))
    val earlier2 = importFrame(frame(3), taken = Some(takenAt(1)))

    val laterSequence = stored(later1).sequenceId.get
    val earlierSequence = stored(earlier1).sequenceId.get
    laterSequence should not be earlierSequence

    val bridge = importFrame(frame(4), taken = Some(takenAt(45)), subSecond = Some("500"))
    bridge.sequenceId shouldBe Some(earlierSequence)
    List(later1, later2, earlier1, earlier2, bridge).map(stored(_).sequenceId).distinct shouldBe List(Some(earlierSequence))
  }

  test("Another camera, another size, a wide gap or a different picture does not pair") {

    /**
     * Setup:
     *
     * A frame, then within the gap the next frame from another camera, a half-size frame and a different picture at the frames'
     * size; and a frame a minute and a half later.
     *
     * Assertions:
     *
     * None of the five is in a Sequence. The different picture is checked to be under each model's default threshold (about 0.25
     * against 0.80 by DINOv2, 0.58 against 0.90 by SigLIP 2), so a broken fixture fails the test. The half-size frame clears both
     * thresholds, so only its size keeps it out.
     */
    val first = importFrame(frame(0))
    val otherCamera = importFrame(frame(1), subSecond = Some("500"), model = Some("Nikon Z8"))
    val halfSize = importFrame(frame(2, scale = 0.5), taken = Some(takenAt(1)))
    val other = importFrame(otherPicture, taken = Some(takenAt(1)), subSecond = Some("500"))
    val late = importFrame(frame(3), taken = Some(takenAt(90)))

    SceneModel.values.foreach {
      model =>
        withClue(model) {
          similarity(model, first, other) should be < defaultThreshold(model)
          similarity(model, first, halfSize) should be >= defaultThreshold(model)
        }
    }
    List(first, otherCamera, halfSize, other, late).map(stored(_).sequenceId).distinct shouldBe List(None)
  }

  test("Frames a minute apart pair, and frames over a minute apart do not") {

    /**
     * Setup:
     *
     * A frame, the next frame of its burst exactly a minute later, and a third a minute and a second after that.
     *
     * Assertions:
     *
     * The first two share a Sequence, since the default gap of a minute is inclusive. The third, 61 s on, has none.
     */
    val first = importFrame(frame(0))
    val minuteLater = importFrame(frame(1), taken = Some(takenAt(60)))
    minuteLater.sequenceId should not be None
    stored(first).sequenceId shouldBe minuteLater.sequenceId

    val over = importFrame(frame(2), taken = Some(takenAt(121)))
    stored(over).sequenceId shouldBe None
    List(first, minuteLater).map(stored(_).sequenceId).distinct shouldBe List(minuteLater.sequenceId)
  }

  test("A frame in another repository does not pair") {

    /**
     * Setup:
     *
     * A frame in one repository, and the next frame of the burst imported into a second repository half a second later.
     *
     * Assertions:
     *
     * Neither is in a Sequence.
     */
    val first = importFrame(frame(0))
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)
    val second = importFrame(frame(1), subSecond = Some("500"))
    stored(second).sequenceId shouldBe None

    switchContextRepo(testContext.repositories.head)
    stored(first).sequenceId shouldBe None
  }

  test("Frames named by Apple's export a second apart share a Sequence, and a frame with EXIF and a camera model joins neither") {

    /**
     * Setup:
     *
     * A frame with no metadata, named as Apple's export names it; a burst frame with the camera's clock and model half a second
     * in; another bare frame named a second after the first; and the different picture at the frames' size, named a second later.
     *
     * Assertions:
     *
     * The two named frames take their capture times from their file names, have their Image quality and hash, and share a
     * Sequence. The different picture has none. The dated frame is in no Sequence: it names a camera and they name none.
     */
    val first = importNamed(frame(0), "Photo Jun 1 2024, 12 00 00 PM.jpg")
    val dated = importFrame(frame(1), subSecond = Some("500"))
    val second = importNamed(frame(2), "Photo Jun 1 2024, 12 00 01 PM.jpg")
    val other = importNamed(otherPicture, "Photo Jun 1 2024, 12 00 02 PM.jpg")

    second.sequenceId should not be None
    List(first, second).map(stored).foreach {
      named =>
        named.originalCreatedAtSource shouldBe Some(CaptureDateSource.FileName)
        named.imageQuality should not be None
        named.perceptualHash should not be None
        named.sequenceId shouldBe second.sequenceId
    }
    stored(other).sequenceId shouldBe None
    stored(dated).originalCreatedAtSource shouldBe Some(CaptureDateSource.ExifOriginal)
    stored(dated).sequenceId shouldBe None
  }

  test("A move out of triage clears the moved frames' Sequence and leaves the frames that stay in it") {

    /**
     * Setup:
     *
     * A burst of three frames in one Sequence, and a folder. One frame is moved to the folder, then the other two.
     *
     * Assertions:
     *
     * The moved frame is sorted with no Sequence, and the two in triage keep theirs. Once every frame has moved, no asset carries
     * the Sequence. A sorted frame moved again between folders stays without one.
     */
    val frames = List(
      importFrame(frame(0)),
      importFrame(frame(1), taken = Some(takenAt(1))),
      importFrame(frame(2), taken = Some(takenAt(2))))
    val sequenceId = frames.last.sequenceId
    sequenceId should not be None
    frames.map(stored(_).sequenceId).distinct shouldBe List(sequenceId)
    val folder = testApp.service.folder.add("sorted")

    testApp.service.library.moveAssetsToFolder(Set(frames.head.persistedId), folder.persistedId)
    val moved = stored(frames.head)
    moved.isTriaged shouldBe false
    moved.sequenceId shouldBe None
    frames.tail.map(stored(_).sequenceId).distinct shouldBe List(sequenceId)

    testApp.service.library.moveAssetsToFolder(frames.tail.map(_.persistedId).toSet, folder.persistedId)
    frames.map(stored(_).sequenceId).distinct shouldBe List(None)
    frames.map(stored(_).isTriaged).distinct shouldBe List(false)
    testApp.txManager.withTransaction(testApp.DAO.asset.countSequenceMembers(Set(sequenceId.get))) shouldBe Nil

    val other = testApp.service.folder.add("other")
    testApp.service.library.moveAssetsToFolder(Set(frames.head.persistedId), other.persistedId)
    stored(frames.head).sequenceId shouldBe None
  }

  test("A frame recycled from triage keeps its Sequence, and a restore brings it back to it") {

    /**
     * Setup:
     *
     * Two frames in one Sequence. One is recycled, then restored; then recycled again and moved out of the trash into a folder.
     *
     * Assertions:
     *
     * In the trash the frame keeps its triage flag and its Sequence, and restored it is back in both. Moved from the trash into a
     * folder it leaves triage and the Sequence, while the other frame keeps it.
     */
    val first = importFrame(frame(0))
    val second = importFrame(frame(1), taken = Some(takenAt(1)))
    val sequenceId = second.sequenceId
    sequenceId should not be None

    testApp.service.library.recycleAssets(Set(first.persistedId))
    val recycled = stored(first)
    recycled.isRecycled shouldBe true
    recycled.isTriaged shouldBe true
    recycled.sequenceId shouldBe sequenceId

    testApp.service.library.restoreRecycledAssets(Set(first.persistedId))
    val restored = stored(first)
    restored.isRecycled shouldBe false
    restored.isTriaged shouldBe true
    restored.sequenceId shouldBe sequenceId

    testApp.service.library.recycleAssets(Set(first.persistedId))
    val folder = testApp.service.folder.add("sorted")
    testApp.service.library.moveAssetsToFolder(Set(first.persistedId), folder.persistedId)
    val sorted = stored(first)
    sorted.isRecycled shouldBe false
    sorted.isTriaged shouldBe false
    sorted.sequenceId shouldBe None
    stored(second).sequenceId shouldBe sequenceId
  }

  test("A frame recycled from triage is a candidate, so the next frame of its burst joins it in the trash") {

    /**
     * Setup:
     *
     * A frame imported and recycled while alone, then the next frame of its burst a second later, then the first restored.
     *
     * Assertions:
     *
     * The second frame shares a Sequence with the recycled first, which keeps its trash and triage flags. Restored, the first
     * keeps the Sequence it took in the trash.
     */
    val first = importFrame(frame(0))
    testApp.service.library.recycleAssets(Set(first.persistedId))
    stored(first).sequenceId shouldBe None

    val second = importFrame(frame(1), taken = Some(takenAt(1)))
    second.sequenceId should not be None
    val recycled = stored(first)
    recycled.isRecycled shouldBe true
    recycled.isTriaged shouldBe true
    recycled.sequenceId shouldBe second.sequenceId

    testApp.service.library.restoreRecycledAssets(Set(first.persistedId))
    val restored = stored(first)
    restored.isRecycled shouldBe false
    restored.sequenceId shouldBe second.sequenceId
    stored(second).sequenceId shouldBe second.sequenceId
  }

  test("A completion that fails after the assignment leaves the Sequences as they were") {

    /**
     * Setup:
     *
     * Two Sequences, of three frames and of two, a minute and a half apart. The triage asset count row is deleted behind the
     * services' back, so counting the next completed asset fails. Then a frame that would bridge the two.
     *
     * Assertions:
     *
     * The bridge's import fails with a `ConstraintException` naming the stat. The assignment ran in the completion's transaction,
     * so both Sequences keep their members and counts, and the bridge is discarded: the repository holds five assets.
     */
    val a =
      List(importFrame(frame(0)), importFrame(frame(1), subSecond = Some("500")), importFrame(frame(2), taken = Some(takenAt(1))))
    val b = List(
      importFrame(frame(3), taken = Some(takenAt(90))),
      importFrame(frame(4), taken = Some(takenAt(90)), subSecond = Some("500")))
    val sequenceA = stored(a.head).sequenceId.get
    val sequenceB = stored(b.head).sequenceId.get
    sequenceA should not be sequenceB

    testApp.txManager.withTransaction {
      update(
        "DELETE FROM stats WHERE repository_id = ? AND dimension = ?",
        testContext.repository.persistedId,
        Stats.TRIAGE_ASSETS)
    }
    val failure = the[ConstraintException] thrownBy importFrame(frame(5), taken = Some(takenAt(45)), subSecond = Some("500"))
    failure.getMessage should include(Stats.TRIAGE_ASSETS)

    a.map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceA))
    b.map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceB))
    val members = testApp.txManager.withTransaction(testApp.DAO.asset.countSequenceMembers(Set(sequenceA, sequenceB)))
    members.map(sequence => sequence.sequenceId -> sequence.members).toMap shouldBe Map(sequenceA -> 3, sequenceB -> 2)
    testApp.txManager.asReadOnly {
      query("SELECT count(*) AS n FROM asset WHERE repository_id = ?", testContext.repository.persistedId)
        .head("n")
        .toString
        .toInt
    } shouldBe 5
  }

  test("A Video has no Image quality, no hash and no Sequence") {

    /**
     * Setup:
     *
     * A synthesized clip imported.
     *
     * Assertions:
     *
     * It is stored with none of the three.
     */
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    val storedVideo = stored(video)
    storedVideo.imageQuality shouldBe None
    storedVideo.perceptualHash shouldBe None
    storedVideo.sequenceId shouldBe None
  }

  test("The Settings decide the gap and the likeness, and the hash distance does not where the embedding decides") {

    /**
     * Setup:
     *
     * Pairs of frames a second apart, each pair past the last one's gap: at 0 s under a 500 ms gap, at 120 s under a hash
     * distance of 0 (their hashes differ by a few bits), and at 240 s under strict thresholds. Then, at the defaults, a frame at
     * 242 s.
     *
     * Assertions:
     *
     * The first pair is no Sequence. The second is one although its hashes differ, since the embeddings decide. The third is no
     * Sequence, and its similarities are checked to lie between the strict thresholds and the defaults, so the thresholds alone
     * part it. The frame at 242 s joins the third pair once the defaults are back: the rule reads the Settings as saved.
     */
    val (first, second) = withSetting(Settings.SEQUENCE_MAX_GAP_MS, "500") {
      (importFrame(frame(0)), importFrame(frame(1), taken = Some(takenAt(1))))
    }
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)

    val (third, fourth) = withSetting(Settings.SEQUENCE_MAX_HASH_DISTANCE, "0") {
      (importFrame(frame(2), taken = Some(takenAt(120))), importFrame(frame(4), taken = Some(takenAt(121))))
    }
    stored(third).perceptualHash should not be stored(fourth).perceptualHash
    fourth.sequenceId should not be None
    List(third, fourth).map(stored(_).sequenceId).distinct shouldBe List(fourth.sequenceId)

    val (fifth, sixth) = withStrictThresholds {
      (importFrame(frame(3), taken = Some(takenAt(240))), importFrame(frame(5), taken = Some(takenAt(241))))
    }
    SceneModel.values.foreach {
      model =>
        withClue(model) {
          similarity(model, fifth, sixth) should be < strictThreshold(model)
          similarity(model, fifth, sixth) should be >= defaultThreshold(model)
        }
    }
    List(fifth, sixth).map(stored(_).sequenceId) shouldBe List(None, None)

    val seventh = importFrame(frame(6), taken = Some(takenAt(242)))
    seventh.sequenceId should not be None
    List(fifth, sixth, seventh).map(stored(_).sequenceId).distinct shouldBe List(seventh.sequenceId)
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
  }

  test("A burst one Scene model misses is caught by the other") {

    /**
     * Setup:
     *
     * For each Scene model, two frames a second apart in a window of their own (0 s, then 120 s), under a strict threshold for
     * that model and the default for the other. Then a third pair at 240 s under both strict thresholds.
     *
     * Assertions:
     *
     * Each of the first two pairs is a Sequence, and its similarities are checked to be under the strict model's threshold and at
     * or above the other's default, so the other model alone pairs it. The third pair, alike by neither, is no Sequence.
     */
    val thresholdOf = Map(
      Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> SceneModel.Dinov2,
      Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY -> SceneModel.Siglip2)
    thresholdOf.zipWithIndex.foreach {
      case ((setting, raised), index) =>
        val other = SceneModel.values.find(_ != raised).value
        val start = index * 120
        val (first, second) = withSetting(setting, strictThreshold(raised).toString) {
          (
            importFrame(frame(2 * index), taken = Some(takenAt(start))),
            importFrame(frame(2 * index + 1), taken = Some(takenAt(start + 1))))
        }
        withClue(raised) {
          similarity(raised, first, second) should be < strictThreshold(raised)
          similarity(other, first, second) should be >= defaultThreshold(other)
          second.sequenceId should not be None
          List(first, second).map(stored(_).sequenceId).distinct shouldBe List(second.sequenceId)
        }
    }

    val (fifth, sixth) = withStrictThresholds {
      (importFrame(frame(4), taken = Some(takenAt(240))), importFrame(frame(5), taken = Some(takenAt(241))))
    }
    SceneModel.values.foreach(model => similarity(model, fifth, sixth) should be < strictThreshold(model))
    List(fifth, sixth).map(stored(_).sequenceId) shouldBe List(None, None)
  }

  test("The candidates are read with their Scene embeddings, and a search result without them") {

    /**
     * Setup:
     *
     * Two frames a second apart, the second's Sequence candidates read through the DAO, then a search of the repository.
     *
     * Assertions:
     *
     * The candidate holds an embedding of each Scene model, of its dimensions. No search record holds either, since the
     * embeddings are bulky columns no general read selects.
     */
    val first = importFrame(frame(0))
    val second = importFrame(frame(1), taken = Some(takenAt(1)))

    // A write transaction: the candidates are locked until it ends
    val candidates = testApp.txManager.withTransaction(testApp.DAO.asset.getSequenceCandidates(second, gapMs = 60000))
    val candidate = candidates.find(_.id == first.id).value
    SceneModel.values.foreach(model => candidate.sceneEmbeddings.of(model).value.values.length shouldBe model.dimensions)

    val results = testApp.service.library.search(new SearchQuery(rpp = 10)).records
    results.map(_.persistedId).toSet shouldBe Set(first.persistedId, second.persistedId)
    results.map(_.sceneEmbeddings).distinct shouldBe List(SceneEmbeddings())
  }

}

object SequenceServiceTests {

  /**
   * A threshold no two frames of the synthesized burst clear: frames a few pixels apart measure 0.982 to 0.998 by DINOv2 and
   * 0.983 to 0.996 by SigLIP 2, while every pair clears the defaults of 0.80 and 0.90.
   */
  private val STRICT_DINOV2_SIMILARITY = 0.999
  private val STRICT_SIGLIP2_SIMILARITY = 0.999
}
