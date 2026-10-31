package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Settings

/**
 * Sequences as the import assigns them. A burst is synthesized at test time: the frames are `images/2.jpg` shifted by a few
 * pixels and brightened a little, so their checksums differ and their hashes are near; the camera's model and clock are seeded as
 * upstream metadata on the asset handed to the pipeline, which the extraction keeps since the file carries none.
 */
@DoNotDiscover class SequenceServiceTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {
  import SequenceServiceTests.*

  private def stored(asset: Asset): Asset = testApp.service.asset.getById(asset.persistedId)

  /**
   * A frame imported with the camera's model, its `Date/Time Original` and its `Sub-Sec Time Original` seeded as upstream
   * metadata, the way the plan's fixtures do, so no EXIF writer is needed
   */
  private def importFrame(
      bytes: Array[Byte],
      taken: Option[String] = Some(TAKEN),
      subSecond: Option[String] = None,
      model: Option[String] = Some(MODEL),
      fileName: String = "burst.jpg"): Asset = {
    val staged = testApp.service.staging.stage(bytes)
    val dataAsset = testApp.service.library.stagedFileToAsset(fileName, staged)
    val metadata = ExtractedMetadata()
    model.foreach(metadata.addValue("Exif IFD0", "Model", _))
    taken.foreach(metadata.addValue("Exif SubIFD", "Date/Time Original", _))
    subSecond.foreach(metadata.addValue("Exif SubIFD", "Sub-Sec Time Original", _))
    testApp.service.library.addAsset(dataAsset.copy(asset = dataAsset.asset.copy(extractedMetadata = metadata)))
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
     * The first frame comes out of its import with no Sequence, since it is alone; the second comes out in a Sequence, and the
     * first is read back in the same one. The different picture has none, and every one of the three has its Image quality and
     * hash.
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
     * A frame a second before midnight and the next frame a second after it, so the candidate window spans two calendar days;
     * then, under a gap of two days, a frame thirty hours before the first, so the window spans five days and the frame's day is
     * strictly inside it.
     *
     * Assertions:
     *
     * The two frames around midnight share a Sequence, and the frame thirty hours earlier joins it under the longer gap: every
     * day of the window is read, not only its first and last.
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
     * Two frames of a burst imported under a hash distance of 0, so they are not paired; then, at the defaults, a move of the
     * first out of triage held open in a transaction of its own while the second is assigned its Sequence.
     *
     * Assertions:
     *
     * The assignment waits for the move (the candidates are read locked), and once the move commits the sorted frame is no
     * candidate: neither frame is in a Sequence, and the first is sorted.
     */
    val (first, second) = withSetting(Settings.SEQUENCE_MAX_HASH_DISTANCE, "0") {
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

  test("The candidates are read through the triage capture-day index") {

    /**
     * Setup:
     *
     * A frame seeded into 5,000 copies whose capture times are spread a minute apart over days, one in ten of them in triage and
     * the rest sorted, analyzed and rolled back once the plan is read, and the statement that reads the candidates of a frame a
     * second after the original.
     *
     * Assertions:
     *
     * The engine's plan reads `asset_triage_date_taken` for it: over a library the planner has rows to choose by, the window's
     * days and times are what the index leads with, and the partial index over the triage set is the smaller of the two
     * capture-day indexes, which otherwise tie by shape (over a handful of rows every index on the repository ties).
     */
    val stamped = stored(importFrame(frame(0)))
    val next = stamped.copy(originalCreatedAt = stamped.originalCreatedAt.map(_.plusSeconds(1)))
    val (sql, values) =
      testApp.DAO.asset.asInstanceOf[altitude.core.dao.jdbc.AssetDao].sequenceCandidatesStatement(next, 3000).get

    val plan = atScale {
      seedCopies(stamped.persistedId, copies = 5000, folders = 1)
      // The copies take their import times, a minute apart, as capture times, and a hash, so they are candidates by shape;
      // every copy whose zero-padded number does not end in 0 is sorted, so the triage set is a tenth of the library
      update("UPDATE asset SET original_created_at = created_at, perceptual_hash = 1 WHERE id <> ?", stamped.persistedId)
      update("UPDATE asset SET is_triaged = FALSE WHERE id <> ? AND substr(id, 36, 1) <> '0'", stamped.persistedId)
    }(lookupPlanOf(sql, values))
    withClue(plan)(plan should include("asset_triage_date_taken"))
  }

  test("A frame that bridges two Sequences merges them into the larger") {

    /**
     * Setup:
     *
     * A burst of three frames within a second (0, .5 and 1 s) and a burst of two five seconds later (6 and 6.5 s), too far apart
     * to pair, so two Sequences; then a frame at 3.5 s, within the gap of the first burst's last frame and the second burst's
     * first.
     *
     * Assertions:
     *
     * Before the bridge, the bursts are two Sequences of three and two. After it, all six frames are in the first burst's
     * Sequence, since it had more members, and nothing carries the second burst's any more.
     */
    val a1 = importFrame(frame(0))
    val a2 = importFrame(frame(1), subSecond = Some("500"))
    val a3 = importFrame(frame(2), taken = Some(takenAt(1)))
    val b1 = importFrame(frame(3), taken = Some(takenAt(6)))
    val b2 = importFrame(frame(4), taken = Some(takenAt(6)), subSecond = Some("500"))

    val sequenceA = stored(a1).sequenceId.get
    val sequenceB = stored(b1).sequenceId.get
    sequenceA should not be sequenceB
    List(a2, a3).map(stored(_).sequenceId) shouldBe List(Some(sequenceA), Some(sequenceA))
    stored(b2).sequenceId shouldBe Some(sequenceB)

    val bridge = importFrame(frame(5), taken = Some(takenAt(3)), subSecond = Some("500"))
    bridge.sequenceId shouldBe Some(sequenceA)
    List(a1, a2, a3, b1, b2, bridge).map(stored(_).sequenceId).distinct shouldBe List(Some(sequenceA))
  }

  test("A merge of Sequences of one size keeps the one captured first") {

    /**
     * Setup:
     *
     * A burst of two frames at 6 and 7 s imported first, then a burst of two at 0 and 1 s, then a frame at 3.5 s that bridges
     * them.
     *
     * Assertions:
     *
     * Two Sequences of two before the bridge; after it, all five are in the Sequence of the burst captured first, although the
     * other Sequence was made first.
     */
    val later1 = importFrame(frame(0), taken = Some(takenAt(6)))
    val later2 = importFrame(frame(1), taken = Some(takenAt(7)))
    val earlier1 = importFrame(frame(2))
    val earlier2 = importFrame(frame(3), taken = Some(takenAt(1)))

    val laterSequence = stored(later1).sequenceId.get
    val earlierSequence = stored(earlier1).sequenceId.get
    laterSequence should not be earlierSequence

    val bridge = importFrame(frame(4), taken = Some(takenAt(3)), subSecond = Some("500"))
    bridge.sequenceId shouldBe Some(earlierSequence)
    List(later1, later2, earlier1, earlier2, bridge).map(stored(_).sequenceId).distinct shouldBe List(Some(earlierSequence))
  }

  test("Another camera, another size, a wide gap or a far hash does not pair") {

    /**
     * Setup:
     *
     * A frame, then within the gap: the next frame from another camera, a half-size copy of a frame, a different picture at the
     * frames' size; and a frame ten seconds later.
     *
     * Assertions:
     *
     * None of the five is in a Sequence. The different picture's hash is checked to be past the default distance, so the test
     * fails on its fixture rather than passing by accident.
     */
    val first = importFrame(frame(0))
    val otherCamera = importFrame(frame(1), subSecond = Some("500"), model = Some("Nikon Z8"))
    val halfSize = importFrame(frame(2, scale = 0.5), taken = Some(takenAt(1)))
    val other = importFrame(otherPicture, taken = Some(takenAt(1)), subSecond = Some("500"))
    val late = importFrame(frame(3), taken = Some(takenAt(10)))

    stored(first).perceptualHash.get.distance(stored(other).perceptualHash.get) should be > DEFAULT_HASH_DISTANCE
    List(first, otherCamera, halfSize, other, late).map(stored(_).sequenceId).distinct shouldBe List(None)
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

  test("A capture time from the file name is measured but never joins a Sequence") {

    /**
     * Setup:
     *
     * A frame with the camera's clock, and the next frame of the burst with the camera's model but no clock, named by the time
     * the file-name rung reads (a second later).
     *
     * Assertions:
     *
     * The named frame's capture time came from its file name, it has its Image quality and hash, and neither frame is in a
     * Sequence.
     */
    val first = importFrame(frame(0))
    val named = importFrame(frame(1), taken = None, fileName = "IMG_20240601_120001.jpg")

    val storedNamed = stored(named)
    storedNamed.originalCreatedAtSource shouldBe Some(CaptureDateSource.FileName)
    storedNamed.imageQuality should not be None
    storedNamed.perceptualHash should not be None
    storedNamed.sequenceId shouldBe None
    stored(first).sequenceId shouldBe None
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
     * the id: the Sequence ended with its last member's move. A sorted frame moved again between folders stays without one.
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
     * In the trash the frame keeps its triage flag and its Sequence (a Sequence whose members are all in the trash still exists),
     * and the restored frame is back in triage and in the Sequence. Moved out of the trash into a folder it leaves triage, so it
     * leaves the Sequence as a move does, while the other frame keeps it.
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

  test("A Video has no Image quality, no hash and no Sequence") {

    /**
     * Setup:
     *
     * A synthesized clip (TestVideos.portrait) imported.
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

  test("The Settings decide the gap and the likeness of a burst") {

    /**
     * Setup:
     *
     * Under a 500 ms gap, two frames a second apart; under a hash distance of 0, two frames a second apart at 10 and 11 s whose
     * hashes differ by a couple of bits; then, at the defaults again, a third frame at 12 s.
     *
     * Assertions:
     *
     * Neither pair is a Sequence under its Setting (the second pair's hashes are checked to differ). The third frame pairs with
     * the frames at 10 and 11 s once the defaults are back, so the rule is read from the Settings as saved.
     */
    val (first, second) = withSetting(Settings.SEQUENCE_MAX_GAP_MS, "500") {
      (importFrame(frame(0)), importFrame(frame(1), taken = Some(takenAt(1))))
    }
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)

    val (third, fourth) = withSetting(Settings.SEQUENCE_MAX_HASH_DISTANCE, "0") {
      (importFrame(frame(2), taken = Some(takenAt(10))), importFrame(frame(4), taken = Some(takenAt(11))))
    }
    stored(third).perceptualHash should not be stored(fourth).perceptualHash
    List(third, fourth).map(stored(_).sequenceId) shouldBe List(None, None)

    val fifth = importFrame(frame(5), taken = Some(takenAt(12)))
    fifth.sequenceId should not be None
    List(third, fourth, fifth).map(stored(_).sequenceId).distinct shouldBe List(fifth.sequenceId)
    List(first, second).map(stored(_).sequenceId) shouldBe List(None, None)
  }
}

object SequenceServiceTests {
  private val MODEL = "Canon EOS R5"
  private val TAKEN = "2024:06:01 12:00:00"
  private val DEFAULT_HASH_DISTANCE = 10

  /** The seeded capture time `seconds` after `TAKEN`, in EXIF's spelling */
  private def takenAt(seconds: Int): String = f"2024:06:01 12:00:$seconds%02d"

  private lazy val base: Array[Byte] = Files.readAllBytes(IntegrationTestUtil.getImportAsset("images/2.jpg").path)

  /**
   * Burst frame `n` of `images/2.jpg`: the file as it is for 0, else a copy shifted `n` pixels each way (the edge filled by its
   * border) and brightened by `6n` levels, re-encoded, so its checksum differs and its hash is near; at `scale` of its size
   */
  private def frame(n: Int, scale: Double = 1.0): Array[Byte] =
    if (n == 0 && scale == 1.0) base
    else
      withDecoded(base) {
        image =>
          val shifted = new Mat()
          val shift = Mat.eye(2, 3, CvType.CV_64F)
          shift.put(0, 2, n.toDouble)
          shift.put(1, 2, n.toDouble)
          try {
            Imgproc.warpAffine(image, shifted, shift, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            Core.add(shifted, new Scalar(6.0 * n, 6.0 * n, 6.0 * n), shifted)
            if (scale != 1.0) Imgproc.resize(shifted, shifted, new Size(), scale, scale, Imgproc.INTER_AREA)
            encode(shifted)
          } finally List(shifted, shift).foreach(_.release())
      }

  /** A different picture (`people/meme-ben.jpg`) at the frames' size, so only the hash tells it from a frame */
  private lazy val otherPicture: Array[Byte] =
    withDecoded(base) {
      frameImage =>
        withDecoded(Files.readAllBytes(IntegrationTestUtil.getImportAsset("people/meme-ben.jpg").path)) {
          other =>
            val resized = new Mat()
            try {
              Imgproc.resize(other, resized, frameImage.size(), 0, 0, Imgproc.INTER_AREA)
              encode(resized)
            } finally resized.release()
        }
    }

  private def withDecoded[A](bytes: Array[Byte])(f: Mat => A): A = {
    val image = Imgcodecs.imdecode(new MatOfByte(bytes*), Imgcodecs.IMREAD_COLOR)
    try f(image)
    finally image.release()
  }

  private def encode(image: Mat): Array[Byte] = {
    val buffer = new MatOfByte()
    try {
      Imgcodecs.imencode(".jpg", image, buffer)
      buffer.toArray
    } finally buffer.release()
  }
}
