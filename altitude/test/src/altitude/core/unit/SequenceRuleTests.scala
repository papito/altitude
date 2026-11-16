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

  /** `(cos θ, sin θ, 0, ...)`: a unit vector at `cosine` (to float32 precision) to the frame's embedding, the first axis */
  private def atCosine(model: SceneModel, cosine: Double): SceneEmbedding = {
    val values = new Array[Float](model.dimensions)
    values(0) = cosine.toFloat
    values(1) = math.sqrt(1 - cosine * cosine).toFloat
    SceneEmbedding(IArray.unsafeFromArray(values))
  }

  /** Embeddings of both models, each at the given cosine to the frame's own */
  private def alikeBy(dinov2: Double, siglip2: Double): SceneEmbeddings =
    SceneEmbeddings(dinov2 = Some(atCosine(SceneModel.Dinov2, dinov2)), siglip2 = Some(atCosine(SceneModel.Siglip2, siglip2)))

  /** A triaged still image as the pipeline completes it: a camera time, a camera model, a hash and both Scene embeddings */
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
     * The frame is eligible, and so are copies with a time from the digitized tag, XMP or the file name, with no capture time,
     * with no Scene embedding (the hash is the floor), or with no device model. A Video, an animated image, a sorted asset, one
     * with the container's creation time and one without a hash are not.
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
     * The frame, its undated copy, the frame with a container time, and the undated copy as a Video, sorted, or without a hash.
     *
     * Assertions:
     *
     * The frame is dated and the undated copy undated, each eligible. A container time is a time but not the camera's, so that
     * frame is neither kind and not eligible. An undated Video, sorted asset or hashless frame is not an undated frame.
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
     * The undated frame and an undated candidate: alike by one model and unlike by the other, or unlike by both; with no
     * embeddings and the hash at or one bit past `sequence.max_hash_distance`; at another size or turned; naming a camera; and
     * the dated frame, naming a camera or none, against the candidate.
     *
     * Assertions:
     *
     * Undated frames match by either model and not by neither, in both directions; without embeddings the hash decides up to the
     * Setting's distance; another size or orientation parts them. The camera plays no part between undated frames, since a frame
     * stripped of its time is stripped of its camera too. A dated and an undated frame never match, however alike.
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
     * The frame and a candidate a second later with the frame's embeddings and hash, then with one condition broken at a time: a
     * 60,001 ms gap either way, another camera, another size or orientation, both similarities just under their default
     * thresholds, or a container time.
     *
     * Assertions:
     *
     * The candidate matches at the default Settings, also exactly a minute apart; each broken condition alone fails it, in both
     * directions.
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
        settings.sequenceMinSimilarity(SceneModel.Siglip2) - 0.01)),
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
     * The frame and a candidate a second later, each also as a copy naming no device model, and as that copy with a file-name
     * time.
     *
     * Assertions:
     *
     * Two frames naming no camera match, and so do two with file-name times. A frame naming a camera and one naming none do not,
     * so an original and its metadata-stripped copy are never a burst; nor do frames of different cameras.
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
     * For each Scene model, a candidate whose similarity by that model is 0.02 above or below its default threshold, and 0 by the
     * other.
     *
     * Assertions:
     *
     * Above its threshold the candidate matches by that model alone and below it does not, in both directions. The thresholds
     * differ (0.80 and 0.90), so 0.85 by both models is in by DINOv2's alone.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    SceneModel.values.foreach {
      model =>
        val threshold = settings.sequenceMinSimilarity(model)
        def at(cosine: Double): Asset = model match
          case SceneModel.Dinov2 => candidate.copy(sceneEmbeddings = alikeBy(cosine, 0.0))
          case SceneModel.Siglip2 => candidate.copy(sceneEmbeddings = alikeBy(0.0, cosine))

        withClue(model) {
          SequenceRule.matches(frame, at(threshold + 0.02), settings) shouldBe true
          SequenceRule.matches(at(threshold + 0.02), frame, settings) shouldBe true
          SequenceRule.matches(frame, at(threshold - 0.02), settings) shouldBe false
          SequenceRule.matches(at(threshold - 0.02), frame, settings) shouldBe false
        }
    }

    settings.sequenceMinSimilarity(SceneModel.Dinov2) should be < 0.85
    settings.sequenceMinSimilarity(SceneModel.Siglip2) should be > 0.85
    SequenceRule.matches(frame, candidate.copy(sceneEmbeddings = alikeBy(0.85, 0.85)), settings) shouldBe true
  }

  test("Frames alike by one Scene model and unlike by the other are one Sequence, and alike by neither are not") {

    /**
     * Setup:
     *
     * Candidates with the frame's hash: alike by DINOv2 (0.95) and unlike by SigLIP 2 (0.5), the reverse, and unlike by both.
     *
     * Assertions:
     *
     * The first two match, each by the model that finds them alike; the third does not, so an identical hash never rescues frames
     * a model compares.
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
     * Candidates with both embeddings and a near hash but low similarities, or a far hash but high DINOv2 similarity; with SigLIP
     * 2 only, alike with a far hash or unlike with a near one; a DINOv2-only frame against a SigLIP 2-only candidate; and neither
     * embedded. The last two with the hash ten or eleven bits away.
     *
     * Assertions:
     *
     * A model both frames have decides alone, whatever the hash. Where they share no model, or have none, the hash decides: ten
     * bits match, eleven do not.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)))
    val nearButUnlike = candidate.copy(perceptualHash = Some(farBy(1)), sceneEmbeddings = alikeBy(0.5, 0.5))
    val farButAlike = candidate.copy(perceptualHash = Some(farBy(30)), sceneEmbeddings = alikeBy(1.0, 0.5))
    SequenceRule.matches(frame, nearButUnlike, settings) shouldBe false
    SequenceRule.matches(frame, farButAlike, settings) shouldBe true

    val siglip2Only = candidate.copy(sceneEmbeddings = SceneEmbeddings(siglip2 = Some(atCosine(SceneModel.Siglip2, 1.0))))
    SequenceRule.matches(frame, siglip2Only.copy(perceptualHash = Some(farBy(30))), settings) shouldBe true
    val unlikeBySiglip2Only = candidate.copy(sceneEmbeddings = SceneEmbeddings(siglip2 = Some(atCosine(SceneModel.Siglip2, 0.5))))
    SequenceRule.matches(frame, unlikeBySiglip2Only.copy(perceptualHash = Some(farBy(1))), settings) shouldBe false

    val dinov2Only = frame.copy(sceneEmbeddings = SceneEmbeddings(dinov2 = Some(atCosine(SceneModel.Dinov2, 1.0))))
    SequenceRule.matches(dinov2Only, siglip2Only.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(dinov2Only, siglip2Only.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false
    SequenceRule.matches(siglip2Only.copy(perceptualHash = Some(farBy(10))), dinov2Only, settings) shouldBe true
    SequenceRule.matches(siglip2Only.copy(perceptualHash = Some(farBy(11))), dinov2Only, settings) shouldBe false

    val bare = frame.copy(sceneEmbeddings = SceneEmbeddings())
    val bareCandidate = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(10))), settings) shouldBe true
    SequenceRule.matches(bare, bareCandidate.copy(perceptualHash = Some(farBy(11))), settings) shouldBe false
  }

  test("The Settings decide the gap, each model's threshold and the hash distance") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later, under Settings with a 500 ms gap, a DINOv2 threshold of 0.5 (exact in float32, so
     * the boundary itself is tested), a SigLIP 2 threshold of 0.97, and a hash distance of 0.
     *
     * Assertions:
     *
     * The shorter gap parts the frames. A DINOv2 similarity of 0.5 is out at the default threshold and in at 0.5 (at least, not
     * above), where 0.49 is out. A SigLIP 2 similarity of 0.94 is in at the default and out at 0.97. At distance 0 an unembedded
     * candidate matches only with an identical hash.
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

    val strictSiglip2 = TestSettings(Map(Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY -> "0.97"))
    val mostlyAlikeBySiglip2 = candidate.copy(sceneEmbeddings = alikeBy(0.0, 0.94))
    SequenceRule.matches(frame, mostlyAlikeBySiglip2, settings) shouldBe true
    SequenceRule.matches(frame, mostlyAlikeBySiglip2, strictSiglip2) shouldBe false

    val identicalOnly = TestSettings(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "0"))
    val unembedded = candidate.copy(sceneEmbeddings = SceneEmbeddings())
    SequenceRule.matches(frame, unembedded.copy(perceptualHash = Some(farBy(1))), settings) shouldBe true
    SequenceRule.matches(frame, unembedded.copy(perceptualHash = Some(farBy(1))), identicalOnly) shouldBe false
    SequenceRule.matches(frame, unembedded, identicalOnly) shouldBe true
  }
}
