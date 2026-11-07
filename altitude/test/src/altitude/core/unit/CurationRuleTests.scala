package altitude.core.unit

import altitude.test.TestSettings
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.BundledFrame
import altitude.core.models.BundleKind
import altitude.core.models.CurationFrame
import altitude.core.models.CurationNotice
import altitude.core.models.CurationProblem
import altitude.core.models.CurationReason.*
import altitude.core.models.CurationSet
import altitude.core.models.ExposureMeasure
import altitude.core.models.Face
import altitude.core.models.ImageQuality
import altitude.core.models.Settings
import altitude.core.util.CurationRule

/** The pure rule that sorts the frames of a Curation set into Bundles, notices and Contenders, on hand-built frames */
@DoNotDiscover class CurationRuleTests extends AnyFunSuite {

  private val settings: Settings = TestSettings()
  private val takenAt = LocalDateTime.of(2024, 6, 1, 12, 0, 0)

  /** Metrics that trip nothing: on the luminance target, with nothing clipped or crushed */
  private val clean = ImageQuality(sharpness = 100, luminance = 0.45, clippedHighlights = 0, crushedShadows = 0, score = 50)

  private val Burst = Some("sequence-1")
  private val OtherBurst = Some("sequence-2")

  private def frame(
      id: String,
      quality: Option[ImageQuality] = Some(clean),
      sequence: Option[String] = None,
      faces: List[Face] = Nil,
      capturedAt: Option[LocalDateTime] = Some(takenAt),
      importedAt: Option[LocalDateTime] = None): CurationFrame =
    CurationFrame(
      asset = Asset(
        id = Some(id),
        userId = "u",
        assetType = AssetType("image", "jpeg", "image/jpeg"),
        fileName = s"$id.jpg",
        checksum = id,
        sizeBytes = 10,
        folderId = "",
        imageQuality = quality,
        sequenceId = sequence,
        originalCreatedAt = capturedAt,
        createdAt = importedAt
      ),
      faces = faces,
      inAlbumOrLocation = false
    )

  private def measuring(
      sharpness: Double = clean.sharpness,
      luminance: Double = clean.luminance,
      clipped: Double = clean.clippedHighlights,
      crushed: Double = clean.crushedShadows): Option[ImageQuality] =
    Some(clean.copy(sharpness = sharpness, luminance = luminance, clippedHighlights = clipped, crushedShadows = crushed))

  private def face(
      person: String,
      quality: Double = 20,
      sharpness: Option[Double] = Some(100),
      width: Int = 100,
      height: Int = 100): Face =
    Face(
      id = Some(s"$person-$quality-$sharpness-$width"),
      x1 = 0,
      y1 = 0,
      width = width,
      height = height,
      personId = Some(person),
      detectionScore = 0.9,
      checksum = "c",
      features = Array.empty[Float],
      quality = quality,
      isEnrolled = true,
      sharpness = sharpness
    )

  private def assess(frames: CurationFrame*): CurationSet = CurationRule.assess(frames.toList, settings)

  private def idsOf(frames: List[CurationFrame]): List[String] = frames.map(_.asset.id.get)

  /** The Bundles of a set and the frames each holds, in the set's own order */
  private def bundlesOf(set: CurationSet): List[(BundleKind, List[String])] =
    set.bundles.map(bundle => bundle.kind -> idsOf(bundle.frames.map(_.frame)))

  private def bundledFrame(set: CurationSet, id: String): BundledFrame =
    set.bundles.flatMap(_.frames).find(_.frame.asset.id.contains(id)).get

  test("Blurry: the same Person's Face sharpness at a similar size, under the ratio of that Person's best") {

    /**
     * Setup:
     *
     * Two frames of a selection sharing a Person at one size, the second's Face at under half, at exactly half and above half of
     * the first's sharpness; then the second's box twice the area, and just over twice.
     *
     * Assertions:
     *
     * Under the ratio the softer frame is Blurry, with the Person, its sharpness and the best as the reason, and the other frame
     * is a Contender. Exactly half is not under it. A box of twice the area is still a similar size, in either direction; a box
     * just over that is not compared.
     */
    val sharp = frame("a", faces = List(face("anna", sharpness = Some(100))))

    val under = assess(sharp, frame("b", faces = List(face("anna", sharpness = Some(49)))))
    bundlesOf(under) shouldBe List(BundleKind.Blurry -> List("b"))
    bundledFrame(under, "b").reasons shouldBe List(FaceSharpness("anna", 49, 100))
    idsOf(under.contenders) shouldBe List("a")

    bundlesOf(assess(sharp, frame("b", faces = List(face("anna", sharpness = Some(50)))))) shouldBe empty

    val twiceTheArea = frame("b", faces = List(face("anna", sharpness = Some(49), width = 200)))
    bundlesOf(assess(sharp, twiceTheArea)) shouldBe List(BundleKind.Blurry -> List("b"))
    val softSmall = frame("c", faces = List(face("anna", sharpness = Some(49), width = 50)))
    bundlesOf(assess(sharp, softSmall)) shouldBe List(BundleKind.Blurry -> List("c"))

    val overTwice = frame("b", faces = List(face("anna", sharpness = Some(49), width = 201)))
    bundlesOf(assess(sharp, overTwice)) shouldBe empty
  }

  test("Blurry: a Person's best is taken only among the Faces at a similar size to the one compared") {

    /**
     * Setup:
     *
     * A Person in three frames with box areas of 1, 1.5 and 3 units, the smallest sharpest, the middle one under half of it, the
     * largest level with the middle one.
     *
     * Assertions:
     *
     * The middle frame is Blurry against the smallest. The largest is compared only with the middle one, level with it, and is
     * not Blurry, though it is under half of the smallest's sharpness.
     */
    val set = assess(
      frame("small", faces = List(face("anna", sharpness = Some(100), width = 100))),
      frame("middle", faces = List(face("anna", sharpness = Some(40), width = 150))),
      frame("large", faces = List(face("anna", sharpness = Some(40), width = 300)))
    )

    bundlesOf(set) shouldBe List(BundleKind.Blurry -> List("middle"))
    bundledFrame(set, "middle").reasons shouldBe List(FaceSharpness("anna", 40, 100))
  }

  test("Blurry: any soft Person is enough, and Face sharpness is never compared across Persons") {

    /**
     * Setup:
     *
     * Frames sharing two Persons, one of them soft in the second frame; frames holding one Person each at very different
     * sharpness.
     *
     * Assertions:
     *
     * The frame with the soft Person is Blurry with that Person alone as the reason. The two Persons are not compared.
     */
    val set = assess(
      frame("a", faces = List(face("anna"), face("ben"))),
      frame("b", faces = List(face("anna"), face("ben", sharpness = Some(10))))
    )
    bundlesOf(set) shouldBe List(BundleKind.Blurry -> List("b"))
    bundledFrame(set, "b").reasons shouldBe List(FaceSharpness("ben", 10, 100))

    bundlesOf(
      assess(
        frame("a", faces = List(face("anna", sharpness = Some(100)))),
        frame("b", faces = List(face("ben", sharpness = Some(1))))
      )) shouldBe empty
  }

  test("Blurry: with no shared Person, a frame of a Sequence falls back to the whole frame against the Sequence's sharpest") {

    /**
     * Setup:
     *
     * Frames of one Sequence with no Faces, the second's whole-frame sharpness under, at and above half of the first's. Then a
     * set mixing a Sequence with a frame in none; a Sequence whose third frame sets the sharpest.
     *
     * Assertions:
     *
     * Under the ratio it is Blurry with the whole-frame reason; exactly half is not. A frame in no Sequence is not judged by it,
     * nor set the sharpest. The reason names the sharpest frame's value.
     */
    val set = assess(frame("a", sequence = Burst), frame("b", measuring(sharpness = 49), Burst))
    bundlesOf(set) shouldBe List(BundleKind.Blurry -> List("b"))
    bundledFrame(set, "b").reasons shouldBe List(FrameSharpness(49, 100))

    bundlesOf(assess(frame("a", sequence = Burst), frame("b", measuring(sharpness = 50), Burst))) shouldBe empty

    val mixed = assess(
      frame("a", sequence = Burst),
      frame("b", measuring(sharpness = 49), Burst),
      frame("loose", measuring(sharpness = 1)),
      frame("sharper", measuring(sharpness = 1000))
    )
    bundlesOf(mixed) shouldBe List(BundleKind.Blurry -> List("b"))

    val three = assess(
      frame("a", measuring(sharpness = 60), Burst),
      frame("b", measuring(sharpness = 49), Burst),
      frame("c", measuring(sharpness = 200), Burst))
    bundlesOf(three) shouldBe List(BundleKind.Blurry -> List("a", "b"))
    bundledFrame(three, "b").reasons shouldBe List(FrameSharpness(49, 200))
  }

  test("Blurry: the whole-frame fallback never applies in a selection or across Sequences") {

    /**
     * Setup:
     *
     * Two frames of very different whole-frame sharpness, with no Sequence; then each in a Sequence of its own.
     *
     * Assertions:
     *
     * Neither set has a Bundle: sharpness across different scenes mostly reflects content.
     */
    bundlesOf(assess(frame("a"), frame("b", measuring(sharpness = 1)))) shouldBe empty
    bundlesOf(assess(frame("a", sequence = Burst), frame("b", measuring(sharpness = 1), OtherBurst))) shouldBe empty
  }

  test("Blurry: the fallback is only for a frame with no comparable Person") {

    /**
     * Setup:
     *
     * Frames of a Sequence with a soft whole frame: sharing a Person sharp in both; sharing a Person whose Face has no sharpness;
     * sharing a Person at very different sizes; holding a Person found nowhere else, in too few of the frames to be missed.
     *
     * Assertions:
     *
     * With a comparable Person sharp in both, the whole frame is not consulted, since a soft background is no fault. The other
     * three have no comparable Person and fall back. A frame with neither comparison, in no Sequence, is never Blurry.
     */
    def sequenceOf(left: Face, right: Face): CurationSet =
      assess(frame("a", sequence = Burst, faces = List(left)), frame("b", measuring(sharpness = 10), Burst, List(right)))
    val wholeFrame = List(BundleKind.Blurry -> List("b"))

    bundlesOf(sequenceOf(face("anna"), face("anna"))) shouldBe empty

    bundlesOf(sequenceOf(face("anna"), face("anna", sharpness = None))) shouldBe wholeFrame
    bundlesOf(sequenceOf(face("anna"), face("anna", width = 400))) shouldBe wholeFrame

    val alone =
      assess(
        frame("a", sequence = Burst),
        frame("b", measuring(sharpness = 10), Burst, List(face("ben"))),
        frame("c", sequence = Burst))
    bundlesOf(alone) shouldBe wholeFrame
    bundledFrame(alone, "b").reasons shouldBe List(FrameSharpness(10, 100))

    bundlesOf(
      assess(
        frame("a", faces = List(face("anna"))),
        frame("b", measuring(sharpness = 10), faces = List(face("anna", sharpness = None)))
      )) shouldBe empty
  }

  test("Blurry: two frames measured over a small face and a large one leave no false Blurry") {

    /**
     * Setup:
     *
     * One Person small and sharp in one frame and large with a quarter of the sharpness in the other, since a Face's sharpness
     * falls as it fills the frame, and whole frames of the same sharpness: in a Sequence, and in a selection.
     *
     * Assertions:
     *
     * The Faces are not compared, at four times the area, and the whole frames are level, so neither set has a Bundle.
     */
    val small = face("anna", sharpness = Some(400), width = 100, height = 100)
    val large = face("anna", sharpness = Some(100), width = 200, height = 200)

    bundlesOf(
      assess(frame("a", sequence = Burst, faces = List(small)), frame("b", sequence = Burst, faces = List(large)))) shouldBe
      empty
    bundlesOf(assess(frame("a", faces = List(small)), frame("b", faces = List(large)))) shouldBe empty
  }

  test("Poor faces: a Face's quality at least the gap below the same Person's best, anywhere in the set") {

    /**
     * Setup:
     *
     * Two frames in no Sequence sharing a Person, the second's quality exactly the gap below the first's, and just inside it.
     * Frames holding one Person each at very different quality. A frame where one of two Persons is poor. A Face with no Person.
     *
     * Assertions:
     *
     * At the gap it has Poor faces, with the Person, the quality and the best as the reason; inside the gap it does not. Quality
     * is never compared across Persons, and a Face with no Person is compared with nothing.
     */
    val set = assess(frame("a", faces = List(face("anna", quality = 20))), frame("b", faces = List(face("anna", quality = 18))))
    bundlesOf(set) shouldBe List(BundleKind.PoorFaces -> List("b"))
    bundledFrame(set, "b").reasons shouldBe List(FaceQuality("anna", 18, 20))
    idsOf(set.contenders) shouldBe List("a")

    bundlesOf(
      assess(
        frame("a", faces = List(face("anna", quality = 20))),
        frame("b", faces = List(face("anna", quality = 18.5))))) shouldBe empty

    bundlesOf(
      assess(
        frame("a", faces = List(face("anna", quality = 22))),
        frame("b", faces = List(face("ben", quality = 15))))) shouldBe empty

    val onePoor = assess(
      frame("a", faces = List(face("anna"), face("ben", quality = 22))),
      frame("b", faces = List(face("anna"), face("ben", quality = 15))))
    bundledFrame(onePoor, "b").reasons shouldBe List(FaceQuality("ben", 15, 22))

    val unnamed = face("anna", quality = 10).copy(personId = None)
    bundlesOf(assess(frame("a", faces = List(face("anna"))), frame("b", faces = List(unnamed)))) shouldBe empty
  }

  test("Poor faces: a Person twice in one frame is compared with the other frames, not with itself") {

    /**
     * Setup:
     *
     * A frame holding a Person twice at very different quality (a Person seen twice in one image), alone in the set with that
     * Person, and then beside a frame holding the Person at a quality between the two.
     *
     * Assertions:
     *
     * Alone, no Pick can fix it, so it is no problem. Beside the other frame, its weaker Face is inside the gap of the other
     * frame and it holds the better Face itself, so only the other frame has Poor faces.
     */
    val twins = frame("twins", faces = List(face("anna", quality = 22), face("anna", quality = 15)))
    bundlesOf(assess(twins, frame("other"))) shouldBe empty

    val set = assess(twins, frame("other", faces = List(face("anna", quality = 16))))
    bundlesOf(set) shouldBe List(BundleKind.PoorFaces -> List("other"))
    bundledFrame(set, "other").reasons shouldBe List(FaceQuality("anna", 16, 22))
  }

  test("Poor faces: in a Sequence, a Person found in at least half of its frames and missing from this one") {

    /**
     * Setup:
     *
     * A Sequence of four frames with a Person in two of them, then in one of them. A Sequence of three with the Person in two and
     * in one. A selection of frames in no Sequence with the Person in two of three. A selection mixing part of a Sequence and a
     * frame in none.
     *
     * Assertions:
     *
     * In exactly half, the frames without the Person have Poor faces, with how many frames hold it as the reason. In fewer than
     * half nothing does. Out of a Sequence the clause does not apply, and it counts only the frames of the Sequence.
     */
    val withAnna = List(face("anna"))

    val half = assess(
      frame("a", sequence = Burst, faces = withAnna),
      frame("b", sequence = Burst, faces = withAnna),
      frame("c", sequence = Burst),
      frame("d", sequence = Burst))
    bundlesOf(half) shouldBe List(BundleKind.PoorFaces -> List("c", "d"))
    bundledFrame(half, "c").reasons shouldBe List(PersonMissing("anna", 2, 4))

    bundlesOf(
      assess(
        frame("a", sequence = Burst, faces = withAnna),
        frame("b", sequence = Burst),
        frame("c", sequence = Burst),
        frame("d", sequence = Burst))) shouldBe empty

    val twoOfThree =
      assess(
        frame("a", sequence = Burst, faces = withAnna),
        frame("b", sequence = Burst, faces = withAnna),
        frame("c", sequence = Burst))
    bundledFrame(twoOfThree, "c").reasons shouldBe List(PersonMissing("anna", 2, 3))

    bundlesOf(
      assess(frame("a", sequence = Burst, faces = withAnna), frame("b", sequence = Burst), frame("c", sequence = Burst))) shouldBe
      empty

    bundlesOf(assess(frame("a", faces = withAnna), frame("b", faces = withAnna), frame("c"))) shouldBe empty

    val mixed = assess(
      frame("a", sequence = Burst, faces = withAnna),
      frame("b", sequence = Burst),
      frame("c", sequence = OtherBurst),
      frame("d"))
    bundlesOf(mixed) shouldBe List(BundleKind.PoorFaces -> List("b"))
    bundledFrame(mixed, "b").reasons shouldBe List(PersonMissing("anna", 1, 2))
  }

  test("Too dark and Too bright: the absolute tests, in and out, anywhere in the set") {

    /**
     * Setup:
     *
     * A frame in no Sequence with one metric on each side of each absolute limit, beside a clean frame.
     *
     * Assertions:
     *
     * Crushed shadows and clipped highlights count at the limit, and luminance past it. Each reason gives the value and the
     * limit.
     */
    def bundled(quality: Option[ImageQuality]): CurationSet = assess(frame("clean"), frame("b", quality))
    val tooDark = List(BundleKind.TooDark -> List("b"))
    val tooBright = List(BundleKind.TooBright -> List("b"))

    val crushed = bundled(measuring(crushed = 0.05))
    bundlesOf(crushed) shouldBe tooDark
    bundledFrame(crushed, "b").reasons shouldBe List(Exposure(ExposureMeasure.CrushedShadows, 0.05, 0.05))
    bundlesOf(bundled(measuring(crushed = 0.04))) shouldBe empty

    val dark = bundled(measuring(luminance = 0.14))
    bundlesOf(dark) shouldBe tooDark
    bundledFrame(dark, "b").reasons shouldBe List(Exposure(ExposureMeasure.DarkLuminance, 0.14, 0.15))
    bundlesOf(bundled(measuring(luminance = 0.15))) shouldBe empty

    val clipped = bundled(measuring(clipped = 0.05))
    bundlesOf(clipped) shouldBe tooBright
    bundledFrame(clipped, "b").reasons shouldBe List(Exposure(ExposureMeasure.ClippedHighlights, 0.05, 0.05))
    bundlesOf(bundled(measuring(clipped = 0.04))) shouldBe empty

    val bright = bundled(measuring(luminance = 0.76))
    bundlesOf(bright) shouldBe tooBright
    bundledFrame(bright, "b").reasons shouldBe List(Exposure(ExposureMeasure.BrightLuminance, 0.76, 0.75))
    bundlesOf(bundled(measuring(luminance = 0.75))) shouldBe empty
  }

  test("Too dark and Too bright: in a Sequence, the luminance gap to the frame nearest the target, on each side of it") {

    /**
     * Setup:
     *
     * Frames of a Sequence, none past an absolute limit, around a frame nearest the target: below the target by more and by less
     * than the gap beyond that frame, then above it. Then a nearest frame on the far side of the target from the frame judged.
     *
     * Assertions:
     *
     * Farther than the gap below is Too dark, above is Too bright, each with the frame's luminance and the nearest's as the
     * reason. Inside the gap nothing holds. The distance is measured from the target, so a nearest frame on the other side counts
     * its own distance and not the distance between the two.
     */
    def sequenceOf(nearest: Double, judged: Double): CurationSet =
      assess(frame("a", measuring(luminance = nearest), Burst), frame("b", measuring(luminance = judged), Burst))

    val darker = sequenceOf(0.45, 0.3)
    bundlesOf(darker) shouldBe List(BundleKind.TooDark -> List("b"))
    bundledFrame(darker, "b").reasons shouldBe List(LuminanceGap(0.3, 0.45))
    bundlesOf(sequenceOf(0.45, 0.36)) shouldBe empty

    val brighter = sequenceOf(0.45, 0.6)
    bundlesOf(brighter) shouldBe List(BundleKind.TooBright -> List("b"))
    bundledFrame(brighter, "b").reasons shouldBe List(LuminanceGap(0.6, 0.45))
    bundlesOf(sequenceOf(0.45, 0.54)) shouldBe empty

    bundledFrame(sequenceOf(0.5, 0.25), "b").reasons shouldBe List(LuminanceGap(0.25, 0.5))
    bundlesOf(sequenceOf(0.5, 0.25)) shouldBe List(BundleKind.TooDark -> List("b"))
    bundlesOf(sequenceOf(0.5, 0.38)) shouldBe empty
    bundlesOf(sequenceOf(0.4, 0.65)) shouldBe List(BundleKind.TooBright -> List("b"))
    bundlesOf(sequenceOf(0.4, 0.52)) shouldBe empty

    // Both sides at once, both against the one frame nearest the target
    val both = assess(
      frame("a", measuring(luminance = 0.45), Burst),
      frame("b", measuring(luminance = 0.3), Burst),
      frame("c", measuring(luminance = 0.6), Burst))
    bundlesOf(both) shouldBe List(BundleKind.TooDark -> List("b"), BundleKind.TooBright -> List("c"))
  }

  test("Too dark and Too bright: the luminance gap never applies in a selection or across Sequences") {

    /**
     * Setup:
     *
     * Two frames far apart in luminance, in no Sequence; each in a Sequence of its own; and a mix where only two share a
     * Sequence.
     *
     * Assertions:
     *
     * Only the frames of one Sequence are compared.
     */
    val onTarget = measuring(luminance = 0.45)
    val low = measuring(luminance = 0.25)
    bundlesOf(assess(frame("a", onTarget), frame("b", low))) shouldBe empty
    bundlesOf(assess(frame("a", onTarget, Burst), frame("b", low, OtherBurst))) shouldBe empty

    val mixed = assess(
      frame("a", measuring(luminance = 0.3), Burst),
      frame("b", measuring(luminance = 0.28), Burst),
      frame("loose", onTarget))
    bundlesOf(mixed) shouldBe empty
  }

  test("Notices: an absolute problem of every frame is a notice on the set and not a Bundle") {

    /**
     * Setup:
     *
     * Two frames both past each absolute limit in turn, at different values; then one of them back inside the limit.
     *
     * Assertions:
     *
     * Each is a notice with the mildest value among the frames, so it is true of every frame, and no Bundle holds them: they are
     * Contenders, and the set is Too close to call. With one frame inside the limit, there is no notice and the other is bundled.
     */
    def bothOf(left: Option[ImageQuality], right: Option[ImageQuality]): CurationSet = assess(frame("a", left), frame("b", right))

    val clipped = bothOf(measuring(clipped = 0.12), measuring(clipped = 0.08))
    clipped.notices shouldBe List(
      CurationNotice(BundleKind.TooBright, List(Exposure(ExposureMeasure.ClippedHighlights, 0.08, 0.05))))
    bundlesOf(clipped) shouldBe empty
    idsOf(clipped.contenders) shouldBe List("a", "b")
    clipped.isTooCloseToCall shouldBe true

    bothOf(measuring(crushed = 0.1), measuring(crushed = 0.2)).notices shouldBe List(
      CurationNotice(BundleKind.TooDark, List(Exposure(ExposureMeasure.CrushedShadows, 0.1, 0.05))))
    bothOf(measuring(luminance = 0.1), measuring(luminance = 0.12)).notices shouldBe List(
      CurationNotice(BundleKind.TooDark, List(Exposure(ExposureMeasure.DarkLuminance, 0.12, 0.15))))
    bothOf(measuring(luminance = 0.9), measuring(luminance = 0.8)).notices shouldBe List(
      CurationNotice(BundleKind.TooBright, List(Exposure(ExposureMeasure.BrightLuminance, 0.8, 0.75))))

    val oneInside = bothOf(measuring(clipped = 0.12), measuring(clipped = 0.01))
    oneInside.notices shouldBe empty
    bundlesOf(oneInside) shouldBe List(BundleKind.TooBright -> List("a"))
  }

  test("Notices: every frame by different tests is still a notice, naming a test all share when there is one") {

    /**
     * Setup:
     *
     * Two frames each Too dark by a different absolute test; two frames sharing one test with one of them also past the other;
     * both problems on every frame at once.
     *
     * Assertions:
     *
     * Different tests give a notice with one reading per test. A shared test gives that reading alone, as the other is not true
     * of every frame. Both problems give both notices, Too dark first.
     */
    val mixed = assess(frame("a", measuring(crushed = 0.1)), frame("b", measuring(luminance = 0.1)))
    mixed.notices shouldBe List(
      CurationNotice(
        BundleKind.TooDark,
        List(Exposure(ExposureMeasure.CrushedShadows, 0.1, 0.05), Exposure(ExposureMeasure.DarkLuminance, 0.1, 0.15))))
    bundlesOf(mixed) shouldBe empty

    val shared = assess(frame("a", measuring(crushed = 0.1, luminance = 0.1)), frame("b", measuring(crushed = 0.2)))
    shared.notices shouldBe List(CurationNotice(BundleKind.TooDark, List(Exposure(ExposureMeasure.CrushedShadows, 0.1, 0.05))))

    val both = assess(frame("a", measuring(crushed = 0.1, clipped = 0.1)), frame("b", measuring(crushed = 0.2, clipped = 0.2)))
    both.notices.map(_.kind) shouldBe List(BundleKind.TooDark, BundleKind.TooBright)
    bundlesOf(both) shouldBe empty
  }

  test("Notices: the relative tests and the other problems still bundle a frame, with no chip for the notice") {

    /**
     * Setup:
     *
     * A Sequence with every frame clipped, one also far brighter than the frame nearest the target. Then frames all crushed, one
     * with a soft Person.
     *
     * Assertions:
     *
     * The notice stands, and the brighter frame is Too bright through the gap alone. The soft frame is Blurry, and the notice's
     * problem is no chip on it.
     */
    val gap = assess(
      frame("a", measuring(clipped = 0.1, luminance = 0.45), Burst),
      frame("b", measuring(clipped = 0.1, luminance = 0.6), Burst))
    gap.notices.map(_.kind) shouldBe List(BundleKind.TooBright)
    bundlesOf(gap) shouldBe List(BundleKind.TooBright -> List("b"))
    bundledFrame(gap, "b").reasons shouldBe List(LuminanceGap(0.6, 0.45))

    val soft = assess(
      frame("a", measuring(crushed = 0.1), faces = List(face("anna"))),
      frame("b", measuring(crushed = 0.1), faces = List(face("anna", sharpness = Some(10)))))
    soft.notices.map(_.kind) shouldBe List(BundleKind.TooDark)
    bundlesOf(soft) shouldBe List(BundleKind.Blurry -> List("b"))
    bundledFrame(soft, "b").chips shouldBe empty
  }

  test("One Bundle per frame: the most serious problem, the others as chips in priority order, and Bundles in that order") {

    /**
     * Setup:
     *
     * A set where each frame has one problem, given in reverse order of seriousness, then a frame with all four and a frame with
     * two.
     *
     * Assertions:
     *
     * The Bundles come in priority order. A frame sits in one Bundle only, with the reasons of that problem, and its others are
     * chips in priority order, each with its reasons. A Contender is in no Bundle.
     */
    val single = assess(
      frame("bright", measuring(clipped = 0.1)),
      frame("dark", measuring(crushed = 0.1)),
      frame("blurry", faces = List(face("anna", sharpness = Some(10)))),
      frame("poor", faces = List(face("anna", quality = 15))),
      frame("clean", faces = List(face("anna")))
    )
    bundlesOf(single) shouldBe List(
      BundleKind.PoorFaces -> List("poor"),
      BundleKind.Blurry -> List("blurry"),
      BundleKind.TooDark -> List("dark"),
      BundleKind.TooBright -> List("bright")
    )
    idsOf(single.contenders) shouldBe List("clean")

    val all = assess(
      frame("clean", faces = List(face("anna"))),
      frame("worst", measuring(crushed = 0.1, clipped = 0.2), faces = List(face("anna", quality = 15, sharpness = Some(10)))),
      frame("two", measuring(clipped = 0.3), faces = List(face("anna", sharpness = Some(10))))
    )
    bundlesOf(all) shouldBe List(BundleKind.PoorFaces -> List("worst"), BundleKind.Blurry -> List("two"))

    val worst = bundledFrame(all, "worst")
    worst.reasons shouldBe List(FaceQuality("anna", 15, 20))
    worst.chips shouldBe List(
      CurationProblem(BundleKind.Blurry, List(FaceSharpness("anna", 10, 100))),
      CurationProblem(BundleKind.TooDark, List(Exposure(ExposureMeasure.CrushedShadows, 0.1, 0.05))),
      CurationProblem(BundleKind.TooBright, List(Exposure(ExposureMeasure.ClippedHighlights, 0.2, 0.05)))
    )
    bundledFrame(all, "two").chips shouldBe List(
      CurationProblem(BundleKind.TooBright, List(Exposure(ExposureMeasure.ClippedHighlights, 0.3, 0.05))))
  }

  test("An unmeasured frame is a Contender whatever its Faces, and its Faces still count for the others") {

    /**
     * Setup:
     *
     * A frame with no metrics and a poor Face, beside a measured frame with the Person's best. The same frame with the best Face,
     * beside a measured frame with a poor one. Frames of a Sequence with an unmeasured one. Every measured frame dark.
     *
     * Assertions:
     *
     * The unmeasured frame is a Contender, not measured, even where its Face is poor or the Person is missing from it. Its Face
     * still sets the Person's best for the measured frame. It sets neither the sharpest nor the nearest of its Sequence. It keeps
     * a notice from saying every frame, since nothing is known of it, and it can be the sole Contender.
     */
    val poorFace = assess(
      frame("unmeasured", None, faces = List(face("anna", quality = 10))),
      frame("measured", faces = List(face("anna", quality = 20))))
    bundlesOf(poorFace) shouldBe empty
    idsOf(poorFace.contenders) shouldBe List("measured", "unmeasured")
    poorFace.contenders.map(_.isMeasured) shouldBe List(true, false)

    val bestFace = assess(
      frame("unmeasured", None, faces = List(face("anna", quality = 20))),
      frame("measured", faces = List(face("anna", quality = 15))))
    bundlesOf(bestFace) shouldBe List(BundleKind.PoorFaces -> List("measured"))
    idsOf(bestFace.contenders) shouldBe List("unmeasured")

    val sequence = assess(
      frame("unmeasured", None, Burst),
      frame("a", measuring(sharpness = 100, luminance = 0.45), Burst),
      frame("b", measuring(sharpness = 60, luminance = 0.4), Burst),
      frame("c", sequence = Burst, faces = List(face("anna")))
    )
    bundlesOf(sequence) shouldBe empty

    val missing = assess(frame("unmeasured", None, Burst), frame("a", sequence = Burst, faces = List(face("anna"))))
    bundlesOf(missing) shouldBe empty

    val dark = assess(frame("unmeasured", None), frame("a", measuring(crushed = 0.1)))
    dark.notices shouldBe empty
    bundlesOf(dark) shouldBe List(BundleKind.TooDark -> List("a"))
    dark.soleContender.map(_.asset.id.get) shouldBe Some("unmeasured")
  }

  test("Too close to call: no Bundle means no suggestion, and a sole Contender is the one suggestion") {

    /**
     * Setup:
     *
     * Three level frames; one frame of three with no problem; two of three with none; every frame with a problem; a lone frame.
     *
     * Assertions:
     *
     * Level frames are all Contenders and Too close to call, with no sole Contender. One frame with no problem is the sole
     * Contender and the set is not Too close to call. Two have no marker. With no Contender there is no marker and the set is not
     * Too close to call. A lone frame has nothing to be separated from, so it has no marker.
     */
    val level = assess(frame("a"), frame("b"), frame("c"))
    level.isTooCloseToCall shouldBe true
    level.soleContender shouldBe None
    idsOf(level.contenders) shouldBe List("a", "b", "c")
    level.notices shouldBe empty

    val poor = face("anna", quality = 15)
    val best = face("anna", quality = 20)
    val mid = face("anna", quality = 17)

    val sole = assess(frame("a", faces = List(best)), frame("b", faces = List(poor)), frame("c", faces = List(mid)))
    sole.isTooCloseToCall shouldBe false
    sole.soleContender.map(_.asset.id.get) shouldBe Some("a")

    val two = assess(frame("a", faces = List(best)), frame("b", faces = List(best)), frame("c", faces = List(poor)))
    two.isTooCloseToCall shouldBe false
    two.soleContender shouldBe None

    val none = assess(frame("a", measuring(crushed = 0.1)), frame("b", measuring(clipped = 0.1)))
    none.contenders shouldBe empty
    none.isTooCloseToCall shouldBe false
    none.soleContender shouldBe None

    assess(frame("a")).soleContender shouldBe None
    CurationRule.assess(Nil, settings) shouldBe CurationSet(Nil, Nil, Nil, Nil)
  }

  test("The frames, the Bundles' frames and the Contenders are in capture order, a frame with no capture time last") {

    /**
     * Setup:
     *
     * Frames handed over out of capture order, two of them Too dark and one with no capture time, and two with one capture time.
     *
     * Assertions:
     *
     * Every list is in capture order, with the ID breaking a tie, whatever order they were handed over in.
     */
    def at(seconds: Int): Option[LocalDateTime] = Some(takenAt.plusSeconds(seconds))

    val set = assess(
      frame("undated", capturedAt = None),
      frame("late-dark", measuring(crushed = 0.1), capturedAt = at(3)),
      frame("tied-b", capturedAt = at(2)),
      frame("early-dark", measuring(crushed = 0.1), capturedAt = at(1)),
      frame("tied-a", capturedAt = at(2))
    )

    idsOf(set.frames) shouldBe List("early-dark", "tied-a", "tied-b", "late-dark", "undated")
    bundlesOf(set) shouldBe List(BundleKind.TooDark -> List("early-dark", "late-dark"))
    idsOf(set.contenders) shouldBe List("tied-a", "tied-b", "undated")
  }

  test("Frames with no capture time, or with one capture time, are in import order, and by ID when imported at one moment") {

    /**
     * Setup:
     *
     * Three frames with no capture time, two of them imported at one moment and the third a second later, and two frames of one
     * capture time imported a second apart, each pair's IDs in the other order.
     *
     * Assertions:
     *
     * The dated pair comes first, in import order; the undated frames follow in import order, the ID breaking the tie of the two
     * imported at one moment, so an undated Sequence shows as it was uploaded rather than by its random IDs.
     */
    def importedAt(seconds: Int): Option[LocalDateTime] = Some(takenAt.plusHours(1).plusSeconds(seconds))

    val set = assess(
      frame("undated-c", capturedAt = None, importedAt = importedAt(0)),
      frame("undated-a", capturedAt = None, importedAt = importedAt(1)),
      frame("undated-b", capturedAt = None, importedAt = importedAt(0)),
      frame("dated-b", importedAt = importedAt(0)),
      frame("dated-a", importedAt = importedAt(1))
    )

    idsOf(set.frames) shouldBe List("dated-b", "dated-a", "undated-b", "undated-c", "undated-a")
    idsOf(set.contenders) shouldBe idsOf(set.frames)
  }

  test("The Settings decide every threshold") {

    /**
     * Setup:
     *
     * For each of the seven Settings, a set the default values leave without a Bundle, assessed under a stricter value.
     *
     * Assertions:
     *
     * Each set gains the Bundle its Setting governs, and carries the Setting's value as a limit where the reason names one.
     */
    def under(key: String, value: String)(frames: CurationFrame*): CurationSet =
      CurationRule.assess(frames.toList, TestSettings(Map(key -> value)))

    bundlesOf(
      under(Settings.CURATION_BLUR_RATIO, "0.9")(
        frame("a", faces = List(face("anna", sharpness = Some(100)))),
        frame("b", faces = List(face("anna", sharpness = Some(80)))))) shouldBe List(BundleKind.Blurry -> List("b"))

    bundlesOf(
      under(Settings.CURATION_FACE_QUALITY_GAP, "0.5")(
        frame("a", faces = List(face("anna", quality = 20))),
        frame("b", faces = List(face("anna", quality = 19))))) shouldBe List(BundleKind.PoorFaces -> List("b"))

    val crushed = under(Settings.CURATION_CRUSHED_FRACTION, "0.01")(frame("a"), frame("b", measuring(crushed = 0.02)))
    bundledFrame(crushed, "b").reasons shouldBe List(Exposure(ExposureMeasure.CrushedShadows, 0.02, 0.01))

    val clipped = under(Settings.CURATION_CLIPPED_FRACTION, "0.01")(frame("a"), frame("b", measuring(clipped = 0.02)))
    bundledFrame(clipped, "b").reasons shouldBe List(Exposure(ExposureMeasure.ClippedHighlights, 0.02, 0.01))

    val dark = under(Settings.CURATION_DARK_LUMINANCE, "0.3")(frame("a"), frame("b", measuring(luminance = 0.2)))
    bundledFrame(dark, "b").reasons shouldBe List(Exposure(ExposureMeasure.DarkLuminance, 0.2, 0.3))

    val bright = under(Settings.CURATION_BRIGHT_LUMINANCE, "0.6")(frame("a"), frame("b", measuring(luminance = 0.7)))
    bundledFrame(bright, "b").reasons shouldBe List(Exposure(ExposureMeasure.BrightLuminance, 0.7, 0.6))

    bundlesOf(
      under(Settings.CURATION_LUMINANCE_GAP, "0.02")(
        frame("a", measuring(luminance = 0.45), Burst),
        frame("b", measuring(luminance = 0.42), Burst))) shouldBe List(BundleKind.TooDark -> List("b"))
  }

  test("The rail's order, the opening frames and the face strip's rows follow the Contenders and the Bundles") {

    /**
     * Setup:
     *
     * A selection of a blown frame and a dark one captured before two Contenders. Anna shows in both Contenders, in the first
     * twice over, and Ben only in the first.
     *
     * Assertions:
     *
     * The rail lists the Contenders, then each Bundle in the rule's order. The set opens on the rail's first two frames, here its
     * first two Contenders. The row of the Person in the most frames comes first and holds that Person's better Face of a frame.
     *
     * Edge cases:
     *
     * One Contender opens pinned beside the first frame of a Bundle, and with none the set opens on the first two frames of its
     * Bundles. A single frame opens alone. A frame with no Person adds no row, and a Face with no Person is left out.
     */
    val betterAnna = face("anna", quality = 22)
    val set = assess(
      frame("blown", measuring(clipped = 0.2)),
      frame("dark", measuring(luminance = 0.05)),
      frame("first", faces = List(face("ben"), face("anna", quality = 21), betterAnna)),
      frame("second", faces = List(face("anna", quality = 21), face("anna", quality = 20).copy(personId = None)))
    )

    idsOf(set.railFrames) shouldBe List("first", "second", "dark", "blown")
    val (pinned, focused) = set.openingFrames
    idsOf(pinned.toList) shouldBe List("first")
    focused.asset.id shouldBe Some("second")

    set.faceRows.map(_.personId) shouldBe List("anna", "ben")
    set.faceRows.head.faces.keySet shouldBe Set("first", "second")
    set.faceRows.head.faces("first").quality shouldBe betterAnna.quality
    set.faceRows(1).faces.keySet shouldBe Set("first")

    val lone = assess(frame("only", sequence = Burst), frame("blurry", measuring(sharpness = 10), Burst))
    idsOf(lone.contenders) shouldBe List("only")
    idsOf(lone.openingFrames._1.toList) shouldBe List("only")
    lone.openingFrames._2.asset.id shouldBe Some("blurry")
    lone.faceRows shouldBe empty

    val allBundled = assess(frame("blown", measuring(clipped = 0.2)), frame("dark", measuring(luminance = 0.05)))
    allBundled.contenders shouldBe empty
    idsOf(allBundled.openingFrames._1.toList) shouldBe List("dark")
    allBundled.openingFrames._2.asset.id shouldBe Some("blown")

    val single = assess(frame("a"))
    single.openingFrames._1 shouldBe None
    single.openingFrames._2.asset.id shouldBe Some("a")
  }
}
