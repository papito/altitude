package altitude.core.unit

import altitude.test.TestSettings
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.AccountSnapshot
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.PerceptualHash
import altitude.core.models.PublicMetadata
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.models.Settings
import altitude.core.util.SequenceRule

/** The pure rule that pairs two still images into a Sequence, on hand-built assets with hand-built Scene embeddings */
@DoNotDiscover class SequenceRuleTests extends AnyFunSuite {

  private val takenAt = LocalDateTime.of(2024, 6, 1, 12, 0, 0, 250_000_000)
  private val hash = PerceptualHash(0x5a5a_f00f_1234_abcdL)
  private val settings: Settings = TestSettings()

  /**
   * A unit vector of the model's dimensions at the given cosine to the frame's own embedding of that model, which is the first
   * axis: `(cos θ, sin θ, 0, ...)`, so its similarity to the frame's is `cosine` (to float32 precision)
   */
  private def atCosine(model: SceneModel, cosine: Double): SceneEmbedding = {
    val values = new Array[Float](model.dimensions)
    values(0) = cosine.toFloat
    values(1) = math.sqrt(1 - cosine * cosine).toFloat
    SceneEmbedding(IArray.unsafeFromArray(values))
  }

  /** Embeddings of both models, each at the given cosine to the frame's own */
  private def alikeBy(dinov2: Double, clip: Double): SceneEmbeddings =
    SceneEmbeddings(dinov2 = Some(atCosine(SceneModel.Dinov2, dinov2)), clip = Some(atCosine(SceneModel.Clip, clip)))

  /**
   * A frame as the pipeline hands it to the completion: a triaged still image with a camera time, a model, a hash and an
   * embedding of each Scene model (the first axis of each model's space)
   */
  private val frame = Asset(
    id = Some("frame-1"),
    ownerUserId = "u",
    originalOwner = AccountSnapshot("u", "U", "u", "u@example.com"),
    assetType = AssetType("image", "jpeg", "image/jpeg"),
    fileName = "burst-1.jpg",
    checksum = "a" * 64,
    sizeBytes = 10,
    folderId = "",
    width = 640,
    height = 724,
    publicMetadata = PublicMetadata(deviceModel = Some("Canon EOS R5")),
    isTriaged = true,
    originalCreatedAt = Some(takenAt),
    originalCreatedAtSource = Some(CaptureDateSource.ExifOriginal),
    perceptualHash = Some(hash),
    sceneEmbeddings = alikeBy(1.0, 1.0)
  )

  /** The hash with its lowest `bits` bits flipped: that many bits of Hamming distance from `hash` */
  private def farBy(bits: Int): PerceptualHash = PerceptualHash(hash.bits ^ ((1L << bits) - 1))

  private def after(ms: Long): LocalDateTime = takenAt.plusNanos(ms * 1_000_000)

  /** The frame as a metadata-stripped copy of it comes: no capture time, so no source for one, and no camera named */
  private val undated = frame.copy(originalCreatedAt = None, originalCreatedAtSource = None, publicMetadata = PublicMetadata())

  test("An image is eligible when it is a triaged still with a hash and a camera or file-name time, or no time at all") {

    /**
     * Setup:
     *
     * A frame as the pipeline completes it, and copies of it with one thing changed each.
     *
     * Assertions:
     *
     * The frame is eligible, and so is one whose time came from the digitized tag, XMP or the file name, one with no capture time
     * at all (an undated frame), one with no Scene embedding at all (the hash is the floor) and one with no device model (a frame
     * saved out of a messaging app names none). A Video, an animated image (a duration), a sorted asset, one whose time is the
     * container's creation time and one without a hash are not.
     */
    SequenceRule.isEligible(frame) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ExifDigitized))) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.XmpCreateDate))) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAt = None, originalCreatedAtSource = None)) shouldBe true
    SequenceRule.isEligible(frame.copy(sceneEmbeddings = SceneEmbeddings())) shouldBe true
    SequenceRule.isEligible(frame.copy(publicMetadata = PublicMetadata())) shouldBe true

    SequenceRule.isEligible(frame.copy(assetType = AssetType("video", "mp4", "video/mp4"))) shouldBe false
    SequenceRule.isEligible(frame.copy(durationMs = Some(1200))) shouldBe false
    SequenceRule.isEligible(frame.copy(isTriaged = false)) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ContainerCreationTime))) shouldBe false
    SequenceRule.isEligible(frame.copy(perceptualHash = None)) shouldBe false
  }

  test("A frame with a camera time is dated, one with no capture time is undated, and one with a container time is neither") {

    /**
     * Setup:
     *
     * The frame; the undated copy of it; the frame with its time from the container's creation; and the undated copy as a Video,
     * sorted, or without a hash.
     *
     * Assertions:
     *
     * The frame is dated and not undated; the undated copy is undated and not dated; each of the two is eligible by its own kind.
     * A frame with a time from the container's clock has a time, so it is not undated, and not from the camera's clock, so it is
     * not dated: it is not eligible. Having no time does not excuse the rest: an undated Video, an undated sorted asset and an
     * undated frame without a hash are not undated frames either.
     */
    SequenceRule.isDated(frame) shouldBe true
    SequenceRule.isUndated(frame) shouldBe false
    SequenceRule.isEligible(frame) shouldBe true

    SequenceRule.isUndated(undated) shouldBe true
    SequenceRule.isDated(undated) shouldBe false
    SequenceRule.isEligible(undated) shouldBe true

    val containerTime = frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ContainerCreationTime))
    SequenceRule.isDated(containerTime) shouldBe false
    SequenceRule.isUndated(containerTime) shouldBe false
    SequenceRule.isEligible(containerTime) shouldBe false

    SequenceRule.isUndated(undated.copy(assetType = AssetType("video", "mp4", "video/mp4"))) shouldBe false
    SequenceRule.isUndated(undated.copy(isTriaged = false)) shouldBe false
    SequenceRule.isUndated(undated.copy(perceptualHash = None)) shouldBe false
  }

  test("Two undated frames at one size match when they look alike; a dated and an undated frame never match") {

    /**
     * Setup:
     *
     * The undated frame and an undated candidate at its size, alike by DINOv2 (0.95) and unlike by CLIP (0.5), the other way
     * round, or unlike by both (0.5); both with no embedding at all, the candidate's hash at the default
     * `sequence.max_hash_distance` or one bit past it; the alike candidate at another width, another height, or the frame's size
     * turned; the alike candidate naming a camera; and the dated frame, naming its camera or none, against the alike undated
     * candidate.
     *
     * Assertions:
     *
     * The undated frames match by either model and not by neither, in both directions; with no model on both, the hash decides at
     * the Setting's distance and no further; another width or height parts them, and a portrait and a landscape frame are two
     * sizes. The camera model plays no part between undated frames (a frame stripped of its time is stripped of its camera too),
     * so one naming a camera still matches. A dated frame and an undated one never match, either way round, however alike and
     * whatever the dated one's camera: the kinds pair only with their own.
     */
    val candidate = undated.copy(id = Some("frame-2"))
    SequenceRule.matches(undated, candidate, settings) shouldBe true
    SequenceRule.matches(candidate, undated, settings) shouldBe true
    SequenceRule.matches(undated, candidate.copy(sceneEmbeddings = alikeBy(0.95, 0.5)), settings) shouldBe true
    SequenceRule.matches(candidate.copy(sceneEmbeddings = alikeBy(0.95, 0.5)), undated, settings) shouldBe true
    SequenceRule.matches(undated, candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.95)), settings) shouldBe true
    SequenceRule.matches(candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.95)), undated, settings) shouldBe true
    SequenceRule.matches(undated, candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.5)), settings) shouldBe false
    SequenceRule.matches(candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.5)), undated, settings) shouldBe false

    val bare = undated.copy(sceneEmbeddings = SceneEmbeddings())
    val bareCandidate = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    val distance = settings.sequenceMaxHashDistance
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(distance))), settings) shouldBe true
    SequenceRule.matches(bareCandidate.copy(perceptualHash = Some(farBy(distance))), bare, settings) shouldBe true
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(distance + 1))), settings) shouldBe false
    SequenceRule.matches(bareCandidate.copy(perceptualHash = Some(farBy(distance + 1))), bare, settings) shouldBe false

    val otherSizes = List(
      candidate.copy(width = 641),
      candidate.copy(height = 723),
      candidate.copy(width = undated.height, height = undated.width)
    )
    otherSizes.foreach {
      other =>
        withClue(other) {
          SequenceRule.matches(undated, other, settings) shouldBe false
          SequenceRule.matches(other, undated, settings) shouldBe false
        }
    }

    val namingCamera = candidate.copy(publicMetadata = PublicMetadata(deviceModel = Some("Canon EOS R5")))
    SequenceRule.matches(undated, namingCamera, settings) shouldBe true
    SequenceRule.matches(namingCamera, undated, settings) shouldBe true

    SequenceRule.matches(frame, candidate, settings) shouldBe false
    SequenceRule.matches(candidate, frame, settings) shouldBe false
    val unnamed = frame.copy(publicMetadata = PublicMetadata())
    SequenceRule.matches(unnamed, candidate, settings) shouldBe false
    SequenceRule.matches(candidate, unnamed, settings) shouldBe false
  }

  test("Two frames match within the gap, on one camera, at one size, looking alike by a Scene model") {

    /**
     * Setup:
     *
     * The frame and a candidate shot a second later with the frame's embeddings (a similarity of 1 by either model) and the
     * frame's hash, then that candidate with one condition broken at a time: a 60,001 ms gap either way, another camera, another
     * width or height, both similarities just under their default thresholds (0.80 for DINOv2, 0.90 for CLIP), a time that is the
     * container's creation time.
     *
     * Assertions:
     *
     * The candidate matches at the default Settings, and at exactly the gap of a minute; each broken condition alone fails it, in
     * both directions of the comparison. A portrait and a landscape frame of one scene are two sizes, so never one Sequence.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SequenceRule.matches(frame, candidate, settings) shouldBe true
    SequenceRule.matches(candidate, frame, settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(60000))), settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(-60000))), settings) shouldBe true

    val broken = List(
      candidate.copy(originalCreatedAt = Some(after(60001))),
      candidate.copy(originalCreatedAt = Some(after(-60001))),
      candidate.copy(publicMetadata = PublicMetadata(deviceModel = Some("Nikon Z8"))),
      candidate.copy(width = 641),
      candidate.copy(height = 723),
      candidate.copy(width = frame.height, height = frame.width),
      candidate.copy(sceneEmbeddings = alikeBy(
        settings.sequenceMinSimilarity(SceneModel.Dinov2) - 0.01,
        settings.sequenceMinSimilarity(SceneModel.Clip) - 0.01)),
      candidate.copy(originalCreatedAtSource = Some(CaptureDateSource.ContainerCreationTime))
    )
    broken.foreach {
      other =>
        withClue(other) {
          SequenceRule.matches(frame, other, settings) shouldBe false
          SequenceRule.matches(other, frame, settings) shouldBe false
        }
    }
  }

  test("Frames on one camera or both naming none match; a frame naming a camera and a frame naming none do not") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later, each also as a copy that names no device model (as a frame saved out of a
     * messaging app, or a metadata-stripped copy of the original, comes), and as that copy with its time from the file name.
     *
     * Assertions:
     *
     * Two frames naming no camera match, in both directions, and so do two with file-name times; a frame that names a camera and
     * one that names none do not, either way round, so an original and its metadata-stripped copy are never a burst; nor do two
     * frames naming different cameras.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    val unnamed = frame.copy(publicMetadata = PublicMetadata())
    val unnamedCandidate = candidate.copy(publicMetadata = PublicMetadata())
    SequenceRule.matches(unnamed, unnamedCandidate, settings) shouldBe true
    SequenceRule.matches(unnamedCandidate, unnamed, settings) shouldBe true

    val named = unnamed.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))
    val namedCandidate = unnamedCandidate.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))
    SequenceRule.matches(named, namedCandidate, settings) shouldBe true
    SequenceRule.matches(namedCandidate, named, settings) shouldBe true

    SequenceRule.matches(frame, unnamedCandidate, settings) shouldBe false
    SequenceRule.matches(unnamedCandidate, frame, settings) shouldBe false
    SequenceRule.matches(unnamed, candidate, settings) shouldBe false
    SequenceRule.matches(candidate, unnamed, settings) shouldBe false

    val otherCamera = candidate.copy(publicMetadata = PublicMetadata(deviceModel = Some("Nikon Z8")))
    SequenceRule.matches(frame, otherCamera, settings) shouldBe false
    SequenceRule.matches(otherCamera, frame, settings) shouldBe false
  }

  test("Each Scene model's similarity is compared with its own threshold, and either model's likeness is enough") {

    /**
     * Setup:
     *
     * For each Scene model, a candidate a second later whose embedding of that model is 0.02 above the model's default threshold
     * (0.80 for DINOv2, 0.90 for CLIP), or 0.02 below it, while its embedding of the other model is orthogonal to the frame's (a
     * similarity of 0, far under either threshold).
     *
     * Assertions:
     *
     * Just above its threshold the candidate matches by that model alone and just below it does not, in both directions; the
     * other model's similarity of 0 neither helps nor hinders. The two thresholds differ, so a similarity of 0.85 by both models
     * is in by DINOv2's threshold although out by CLIP's: one model's likeness is enough.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SceneModel.values.foreach {
      model =>
        val threshold = settings.sequenceMinSimilarity(model)
        def at(cosine: Double): Asset = model match
          case SceneModel.Dinov2 => candidate.copy(sceneEmbeddings = alikeBy(cosine, 0.0))
          case SceneModel.Clip => candidate.copy(sceneEmbeddings = alikeBy(0.0, cosine))

        withClue(model) {
          SequenceRule.matches(frame, at(threshold + 0.02), settings) shouldBe true
          SequenceRule.matches(at(threshold + 0.02), frame, settings) shouldBe true
          SequenceRule.matches(frame, at(threshold - 0.02), settings) shouldBe false
          SequenceRule.matches(at(threshold - 0.02), frame, settings) shouldBe false
        }
    }

    settings.sequenceMinSimilarity(SceneModel.Dinov2) should be < 0.85
    settings.sequenceMinSimilarity(SceneModel.Clip) should be > 0.85
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.85, 0.85)), settings) shouldBe true
  }

  test("Frames alike by one Scene model and unlike by the other are one Sequence, and alike by neither are not") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later with the frame's hash, alike by DINOv2 (0.95) and unlike by CLIP (0.5); one the
     * other way round, alike by CLIP (0.95) and unlike by DINOv2 (0.5); and one unlike by both (0.5). The likenesses are past the
     * default thresholds on the side they are meant to be.
     *
     * Assertions:
     *
     * The first two candidates match, each by the model that finds them alike; the third does not. The hashes are identical
     * throughout, so the hash is not what decides: two frames compared by any model are never rescued by their hashes.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.95, 0.5)), settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.95)), settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.5)), settings) shouldBe false
  }

  test("The hash decides only when no Scene model's embedding is on both frames") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later, in these shapes: with both models' embeddings, a near hash (one bit away) and
     * both similarities at 0.5, or a far hash (thirty bits away) and a DINOv2 similarity of 1; with a CLIP embedding only, alike
     * by it (1.0) and the hash thirty bits away, or unlike by it (0.5) and the hash one bit away; the frame with a DINOv2
     * embedding only against that CLIP-only candidate, the hash ten or eleven bits away; and neither with any embedding.
     *
     * Assertions:
     *
     * With both embeddings the similarities decide alone: the near hash with the low similarities is no match, the far hash with
     * the high DINOv2 similarity is. A candidate the frame shares one model with is decided by that model, whatever its hash: in
     * when alike by it, out when unlike. Where the two frames share no model (one embedded by DINOv2 alone, the other by CLIP
     * alone), and where neither has any embedding, the hash decides: ten bits match, eleven do not.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    val nearButUnlike = candidate.copy(perceptualHash = Some(farBy(1)), sceneEmbeddings = alikeBy(0.5, 0.5))
    val farButAlike = candidate.copy(perceptualHash = Some(farBy(30)), sceneEmbeddings = alikeBy(1.0, 0.5))
    SequenceRule.matches(frame, nearButUnlike, settings) shouldBe false
    SequenceRule.matches(frame, farButAlike, settings) shouldBe true

    val clipOnly = candidate.copy(sceneEmbeddings = SceneEmbeddings(clip = Some(atCosine(SceneModel.Clip, 1.0))))
    SequenceRule.matches(frame, clipOnly.copy(perceptualHash = Some(farBy(30))), settings) shouldBe true
    val unlikeByClipOnly = candidate.copy(sceneEmbeddings = SceneEmbeddings(clip = Some(atCosine(SceneModel.Clip, 0.5))))
    SequenceRule.matches(frame, unlikeByClipOnly.copy(perceptualHash = Some(farBy(1))), settings) shouldBe false

    val dinov2Only = frame.copy(sceneEmbeddings = SceneEmbeddings(dinov2 = Some(atCosine(SceneModel.Dinov2, 1.0))))
    SequenceRule.matches(dinov2Only, clipOnly.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(dinov2Only, clipOnly.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false
    SequenceRule.matches(clipOnly.copy(perceptualHash = Some(farBy(10))), dinov2Only, settings) shouldBe true
    SequenceRule.matches(clipOnly.copy(perceptualHash = Some(farBy(11))), dinov2Only, settings) shouldBe false

    val bare = frame.copy(sceneEmbeddings = SceneEmbeddings())
    val bareCandidate = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false
  }

  test("The Settings decide the gap, each model's threshold and the hash distance") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later; Settings with a 500 ms gap; a DINOv2 threshold of 0.5 (exactly representable in
     * float32, so the boundary itself can be tested) against candidates at a DINOv2 similarity of 0.5 and 0.49, unlike by CLIP; a
     * CLIP threshold of 0.95 against a candidate at a CLIP similarity of 0.92, unlike by DINOv2; and a hash distance of 0 against
     * a candidate with no embedding at all whose hash is one bit away, or identical.
     *
     * Assertions:
     *
     * The candidate matches at the defaults, not under the shorter gap. A DINOv2 similarity of 0.5 is out at the default
     * threshold and in at a threshold of 0.5 (at least, not above), where 0.49 is out. A CLIP similarity of 0.92 is in at the
     * default CLIP threshold and out at 0.95. Under the zero distance the embedding-less candidate matches only with an identical
     * hash.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SequenceRule.matches(frame, candidate, settings) shouldBe true

    val shortGap = TestSettings(Map(Settings.SEQUENCE_MAX_GAP_MS -> "500"))
    SequenceRule.matches(frame, candidate, shortGap) shouldBe false
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(500))), shortGap) shouldBe true

    val halfAlike = candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.0))
    val lenientDinov2 = TestSettings(Map(Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> "0.5"))
    SequenceRule.matches(frame, halfAlike, settings) shouldBe false
    SequenceRule.matches(frame, halfAlike, lenientDinov2) shouldBe true
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.49, 0.0)), lenientDinov2) shouldBe false

    val strictClip = TestSettings(Map(Settings.SEQUENCE_CLIP_MIN_SIMILARITY -> "0.95"))
    val mostlyAlikeByClip = candidate.copy(sceneEmbeddings = alikeBy(0.0, 0.92))
    SequenceRule.matches(frame, mostlyAlikeByClip, settings) shouldBe true
    SequenceRule.matches(frame, mostlyAlikeByClip, strictClip) shouldBe false

    val identicalOnly = TestSettings(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "0"))
    val unembedded = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    SequenceRule.matches(frame, unembedded.copy(perceptualHash = Some(farBy(1))), settings) shouldBe true
    SequenceRule.matches(frame, unembedded.copy(perceptualHash = Some(farBy(1))), identicalOnly) shouldBe false
    SequenceRule.matches(frame, unembedded, identicalOnly) shouldBe true
  }
}
