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
import altitude.core.models.Settings
import altitude.core.util.SequenceRule

/** The pure rule that pairs two still images into a Sequence, on hand-built assets */
@DoNotDiscover class SequenceRuleTests extends AnyFunSuite {

  private val takenAt = LocalDateTime.of(2024, 6, 1, 12, 0, 0, 250_000_000)
  private val hash = PerceptualHash(0x5a5a_f00f_1234_abcdL)
  private val settings: Settings = TestSettings()

  /** A frame as the pipeline hands it to the completion: a triaged still image with a camera time, a model and a hash */
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
    perceptualHash = Some(hash)
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
     * The frame is eligible, and so is one whose time came from the digitized tag or XMP. A Video, an animated image (a
     * duration), a sorted asset, an asset with no capture time, one whose time came from the file name, one without a hash and
     * one without a device model are not.
     */
    SequenceRule.isEligible(frame) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ExifDigitized))) shouldBe true
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.XmpCreateDate))) shouldBe true

    SequenceRule.isEligible(frame.copy(assetType = AssetType("video", "mp4", "video/mp4"))) shouldBe false
    SequenceRule.isEligible(frame.copy(durationMs = Some(1200))) shouldBe false
    SequenceRule.isEligible(frame.copy(isTriaged = false)) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAt = None, originalCreatedAtSource = None)) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.FileName))) shouldBe false
    SequenceRule.isEligible(frame.copy(originalCreatedAtSource = Some(CaptureDateSource.ContainerCreationTime))) shouldBe false
    SequenceRule.isEligible(frame.copy(perceptualHash = None)) shouldBe false
    SequenceRule.isEligible(frame.copy(publicMetadata = PublicMetadata())) shouldBe false
  }

  test("Two frames match within the gap, on one camera, at one size, with hashes within the distance") {

    /**
     * Setup:
     *
     * The frame and a candidate shot a second later with a hash ten bits away, then that candidate with one condition broken at a
     * time: a 3,001 ms gap either way, another camera, another width or height, eleven bits of hash distance, a time from the
     * file name.
     *
     * Assertions:
     *
     * The candidate matches at the default Settings, and at exactly the gap; each broken condition alone fails it, in both
     * directions of the comparison.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)), perceptualHash = Some(farBy(10)))
    SequenceRule.matches(frame, candidate, settings) shouldBe true
    SequenceRule.matches(candidate, frame, settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(3000))), settings) shouldBe true
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(-3000))), settings) shouldBe true

    val broken = List(
      candidate.copy(originalCreatedAt = Some(after(3001))),
      candidate.copy(originalCreatedAt = Some(after(-3001))),
      candidate.copy(publicMetadata = PublicMetadata(deviceModel = Some("Nikon Z8"))),
      candidate.copy(width = 641),
      candidate.copy(height = 723),
      candidate.copy(perceptualHash = Some(farBy(11))),
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

  test("The Settings decide the gap and the hash distance") {

    /**
     * Setup:
     *
     * The frame and a candidate a second later with a hash one bit away; Settings with a 500 ms gap, and Settings with a hash
     * distance of 0.
     *
     * Assertions:
     *
     * The candidate matches at the defaults, not under the shorter gap, and under the zero distance only when the hashes are
     * identical.
     */
    val candidate = frame.copy(id = Some("frame-2"), originalCreatedAt = Some(after(1000)), perceptualHash = Some(farBy(1)))
    SequenceRule.matches(frame, candidate, settings) shouldBe true

    val shortGap = TestSettings(Map(Settings.SEQUENCE_MAX_GAP_MS -> "500"))
    SequenceRule.matches(frame, candidate, shortGap) shouldBe false
    SequenceRule.matches(frame, candidate.copy(originalCreatedAt = Some(after(500))), shortGap) shouldBe true

    val identicalOnly = TestSettings(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "0"))
    SequenceRule.matches(frame, candidate, identicalOnly) shouldBe false
    SequenceRule.matches(frame, candidate.copy(perceptualHash = Some(hash)), identicalOnly) shouldBe true
  }
}
