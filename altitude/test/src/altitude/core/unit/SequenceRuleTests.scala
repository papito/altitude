package altitude.core.unit

import altitude.test.TestSettings
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

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
  private val byClip: Settings = TestSettings(Map(Settings.SEQUENCE_EMBEDDING_MODEL -> SceneModel.Clip.dbValue))

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
    userId = "u",
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

  test("An image is eligible when it is a triaged still with a camera time, a model and a hash") {

    /**
     * Setup:
     *
     * A frame as the pipeline completes it, and copies of it with one thing changed each.
     *
     * Assertions:
     *
     * The frame is eligible, and so is one whose time came from the digitized tag or XMP, and one with no Scene embedding at all
     * (the hash is the floor). A Video, an animated image (a duration), a sorted asset, an asset with no capture time, one whose
     * time came from the file name, one without a hash and one without a device model are not.
     */
    SequenceRule.isEligible(frame) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ExifDigitized))) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.XmpCreateDate))) shouldBe true
    SequenceRule.isEligible(frame.copy(sceneEmbeddings = SceneEmbeddings())) shouldBe true

    SequenceRule.isEligible(frame.copy(assetType = AssetType("video", "mp4", "video/mp4"))) shouldBe false
    SequenceRule.isEligible(frame.copy(durationMs = Some(1200))) shouldBe false
    SequenceRule.isEligible(frame.copy(isTriaged = false)) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAt = None, originalCreatedAtSource = None)) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ContainerCreationTime))) shouldBe false
    SequenceRule.isEligible(frame.copy(perceptualHash = None)) shouldBe false
    SequenceRule.isEligible(frame.copy(publicMetadata = PublicMetadata())) shouldBe false
  }

  test("Two frames match within the gap, on one camera, at one size, looking alike by the chosen Scene model") {

    /**
     * Setup:
     *
     * The frame and a candidate shot a second later with the frame's embeddings (a similarity of 1 by either model) and the
     * frame's hash, then that candidate with one condition broken at a time: a 60,001 ms gap either way, another camera, another
     * width or height, a DINOv2 similarity just under the default threshold of 0.80, a time from the file name.
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
      candidate.copy(sceneEmbeddings = alikeBy(settings.sequenceMinSimilarity(SceneModel.Dinov2) - 0.01, 1.0)),
      candidate.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))
    )
    broken.foreach {
      other =>
        withClue(other) {
          SequenceRule.matches(frame, other, settings) shouldBe false
          SequenceRule.matches(other, frame, settings) shouldBe false
        }
    }
  }

  test("Each Scene model's similarity is compared with its own threshold") {

    /**
     * Setup:
     *
     * For each Scene model, Settings choosing it, and a candidate a second later whose embedding of that model is 0.02 above the
     * model's default threshold (0.80 for DINOv2, 0.90 for CLIP), or 0.02 below it, while its embedding of the other model is
     * orthogonal to the frame's (a similarity of 0).
     *
     * Assertions:
     *
     * Just above its threshold the candidate matches under that model and just below it does not, in both directions; the other
     * model's similarity of 0 plays no part. The two thresholds differ, so a similarity between them (0.85) is in by DINOv2's and
     * out by CLIP's.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SceneModel.values.foreach {
      model =>
        val chosen = TestSettings(Map(Settings.SEQUENCE_EMBEDDING_MODEL -> model.dbValue))
        val threshold = chosen.sequenceMinSimilarity(model)
        def at(cosine: Double): Asset = model match
          case SceneModel.Dinov2 => candidate.copy(sceneEmbeddings = alikeBy(cosine, 0.0))
          case SceneModel.Clip => candidate.copy(sceneEmbeddings = alikeBy(0.0, cosine))

        withClue(model) {
          SequenceRule.matches(frame, at(threshold + 0.02), chosen) shouldBe true
          SequenceRule.matches(at(threshold + 0.02), frame, chosen) shouldBe true
          SequenceRule.matches(frame, at(threshold - 0.02), chosen) shouldBe false
          SequenceRule.matches(at(threshold - 0.02), frame, chosen) shouldBe false
        }
    }

    settings.sequenceMinSimilarity(SceneModel.Dinov2) should be < 0.85
    byClip.sequenceMinSimilarity(SceneModel.Clip) should be > 0.85
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.85, 0.85)), settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.85, 0.85)), byClip) shouldBe false
  }

  test("The Setting chooses which Scene model's embedding is read") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later with the frame's hash, alike by DINOv2 (0.95) and unlike by CLIP (0.5); and one
     * the other way round, alike by CLIP (0.95) and unlike by DINOv2 (0.5). Both similarities are past the default thresholds on
     * the side they are meant to be.
     *
     * Assertions:
     *
     * The first candidate matches under `dinov2` and not under `clip`; the second under `clip` and not under `dinov2`. The hashes
     * are identical throughout, so the hash is not what decides.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    val alikeByDinov2 = candidate.copy(sceneEmbeddings = alikeBy(0.95, 0.5))
    val alikeByClip = candidate.copy(sceneEmbeddings = alikeBy(0.5, 0.95))

    SequenceRule.matches(frame, alikeByDinov2, settings) shouldBe true
    SequenceRule.matches(frame, alikeByDinov2, byClip) shouldBe false
    SequenceRule.matches(frame, alikeByClip, byClip) shouldBe true
    SequenceRule.matches(frame, alikeByClip, settings) shouldBe false
  }

  test("The hash decides only when either frame lacks the chosen Scene model's embedding") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later, in four shapes: with both models' embeddings, a near hash (one bit away) and a
     * DINOv2 similarity of 0.5, or a far hash (thirty bits away) and a similarity of 1; with no DINOv2 embedding (a CLIP one
     * only), the hash ten or eleven bits away; the frame itself with no DINOv2 embedding against a candidate with both; and
     * neither with any embedding.
     *
     * Assertions:
     *
     * With both embeddings the similarity decides alone: the near hash with the low similarity is no match, the far hash with the
     * high similarity is. Where either frame lacks the DINOv2 embedding the hash decides: ten bits match, eleven do not,
     * whichever side lacks it, and when neither has any embedding. Under `clip`, a candidate with a CLIP embedding and no DINOv2
     * one is decided by the CLIP similarity, whatever its hash.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    val nearButUnlike = candidate.copy(perceptualHash = Some(farBy(1)), sceneEmbeddings = alikeBy(0.5, 1.0))
    val farButAlike = candidate.copy(perceptualHash = Some(farBy(30)), sceneEmbeddings = alikeBy(1.0, 0.5))
    SequenceRule.matches(frame, nearButUnlike, settings) shouldBe false
    SequenceRule.matches(frame, farButAlike, settings) shouldBe true

    val clipOnly = SceneEmbeddings(clip = Some(atCosine(SceneModel.Clip, 1.0)))
    val noDinov2 = candidate.copy(sceneEmbeddings = clipOnly)
    SequenceRule.matches(frame, noDinov2.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(frame, noDinov2.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false

    val frameWithoutDinov2 = frame.copy(sceneEmbeddings = clipOnly)
    SequenceRule.matches(frameWithoutDinov2, candidate.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(frameWithoutDinov2, candidate.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false

    val bare = frame.copy(sceneEmbeddings = SceneEmbeddings())
    val bareCandidate = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false

    SequenceRule.matches(frame, noDinov2.copy(perceptualHash = Some(farBy(30))), byClip) shouldBe true
    val unlikeByClip = candidate.copy(sceneEmbeddings = SceneEmbeddings(clip = Some(atCosine(SceneModel.Clip, 0.5))))
    SequenceRule.matches(frame, unlikeByClip.copy(perceptualHash = Some(farBy(1))), byClip) shouldBe false
  }

  test("The Settings decide the gap, each model's threshold and the hash distance") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later; Settings with a 500 ms gap; a DINOv2 threshold of 0.5 (exactly representable in
     * float32, so the boundary itself can be tested) against candidates at a similarity of 0.5 and 0.49; a CLIP threshold of 0.95
     * under `clip` against a candidate at 0.92; and a hash distance of 0 against a candidate with no DINOv2 embedding whose hash
     * is one bit away, or identical.
     *
     * Assertions:
     *
     * The candidate matches at the defaults, not under the shorter gap. A similarity of 0.5 is out at the default DINOv2
     * threshold and in at a threshold of 0.5 (at least, not above), where 0.49 is out. A CLIP similarity of 0.92 is in at the
     * default CLIP threshold and out at 0.95. Under the zero distance the embedding-less candidate matches only with an identical
     * hash.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SequenceRule.matches(frame, candidate, settings) shouldBe true

    val shortGap = TestSettings(Map(Settings.SEQUENCE_MAX_GAP_MS -> "500"))
    SequenceRule.matches(frame, candidate, shortGap) shouldBe false
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(500))), shortGap) shouldBe true

    val halfAlike = candidate.copy(sceneEmbeddings = alikeBy(0.5, 1.0))
    val lenientDinov2 = TestSettings(Map(Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> "0.5"))
    SequenceRule.matches(frame, halfAlike, settings) shouldBe false
    SequenceRule.matches(frame, halfAlike, lenientDinov2) shouldBe true
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.49, 1.0)), lenientDinov2) shouldBe false

    val strictClip = TestSettings(
      Map(Settings.SEQUENCE_EMBEDDING_MODEL -> SceneModel.Clip.dbValue, Settings.SEQUENCE_CLIP_MIN_SIMILARITY -> "0.95"))
    val mostlyAlikeByClip = candidate.copy(sceneEmbeddings = alikeBy(1.0, 0.92))
    SequenceRule.matches(frame, mostlyAlikeByClip, byClip) shouldBe true
    SequenceRule.matches(frame, mostlyAlikeByClip, strictClip) shouldBe false

    val identicalOnly = TestSettings(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "0"))
    val noDinov2 = candidate.copy(sceneEmbeddings = SceneEmbeddings(clip = Some(atCosine(SceneModel.Clip, 1.0))))
    SequenceRule.matches(frame, noDinov2.copy(perceptualHash = Some(farBy(1))), settings) shouldBe true
    SequenceRule.matches(frame, noDinov2.copy(perceptualHash = Some(farBy(1))), identicalOnly) shouldBe false
    SequenceRule.matches(frame, noDinov2, identicalOnly) shouldBe true
  }
}
