package altitude.core.util

import java.time.LocalDateTime

import altitude.core.models.BundledFrame
import altitude.core.models.BundleKind
import altitude.core.models.CurationBundle
import altitude.core.models.CurationFrame
import altitude.core.models.CurationNotice
import altitude.core.models.CurationProblem
import altitude.core.models.CurationReason
import altitude.core.models.CurationReason._
import altitude.core.models.CurationSet
import altitude.core.models.ExposureMeasure
import altitude.core.models.Face
import altitude.core.models.ImageQuality
import altitude.core.models.Settings

/**
 * The rule that sorts the frames of a Curation set into Bundles, notices and Contenders. Pure over the models, so it is tested on
 * its own, and it runs when a set is read: nothing it decides is stored, so a changed threshold applies from the next read.
 *
 * The relative tests compare only what is comparable. Sharpness and exposure across different scenes mostly reflect content, so
 * the whole-frame and luminance comparisons run only among frames of one Sequence, while the same-Person tests run anywhere in
 * the set. Face sharpness and Face quality are never compared across Persons. It never reads the Image quality's score.
 */
object CurationRule:

  /**
   * One Person's Faces compare on sharpness only when their box areas are within this factor, as it falls as a face fills the
   * frame
   */
  private val SIMILAR_SIZE_FACTOR = 2L

  def assess(frames: List[CurationFrame], settings: Settings): CurationSet =
    val inCaptureOrder = frames.sorted(captureOrder)
    val darkNotice = noticeOf(BundleKind.TooDark, inCaptureOrder.map(absoluteDark(_, settings)))
    val brightNotice = noticeOf(BundleKind.TooBright, inCaptureOrder.map(absoluteBright(_, settings)))

    val assessed = inCaptureOrder.map {
      frame =>
        val others = inCaptureOrder.filterNot(_ eq frame)
        val sequenceMates = others.filter(other => frame.asset.sequenceId.exists(other.asset.sequenceId.contains))
        val (darker, brighter) = luminanceGap(frame, sequenceMates, settings).toList
          .partition(_.luminance < ImageMeasurement.TARGET_LUMINANCE)

        // An absolute problem of every frame is the set's notice, so it no longer tells this frame from the others
        val dark = if darkNotice.isDefined then Nil else absoluteDark(frame, settings)
        val bright = if brightNotice.isDefined then Nil else absoluteBright(frame, settings)

        val problems =
          if frame.isMeasured then
            List(
              CurationProblem(BundleKind.PoorFaces, poorFaces(frame, others, sequenceMates, settings)),
              CurationProblem(BundleKind.Blurry, blurry(frame, others, sequenceMates, settings)),
              CurationProblem(BundleKind.TooDark, dark ++ darker),
              CurationProblem(BundleKind.TooBright, bright ++ brighter)
            ).filter(_.reasons.nonEmpty)
          else Nil

        frame -> problems
    }

    val bundledByKind = assessed
      .collect { case (frame, main :: chips) => main.kind -> BundledFrame(frame, main.reasons, chips) }
      .groupMap(_._1)(_._2)

    CurationSet(
      frames = inCaptureOrder,
      notices = darkNotice.toList ++ brightNotice,
      bundles = BundleKind.values.toList.flatMap(kind => bundledByKind.get(kind).map(CurationBundle(kind, _))),
      contenders = assessed.collect { case (frame, Nil) => frame }
    )

  /**
   * Capture order, a frame with no capture time last and the ID breaking a tie, so the order does not depend on how they were
   * read
   */
  private val captureOrder: Ordering[CurationFrame] =
    given Ordering[LocalDateTime] = Ordering.fromLessThan(_.isBefore(_))
    Ordering.by(frame => (frame.asset.originalCreatedAt.isEmpty, frame.asset.originalCreatedAt, frame.asset.id))

  /**
   * A Face at least the gap under the same Person's best, or, in a Sequence, a Person in at least half of its frames with no Face
   * here. The best is taken from the other frames, as a Person twice in this one (twins) is nothing a Pick can tell apart.
   */
  private def poorFaces(
      frame: CurationFrame,
      others: List[CurationFrame],
      sequenceMates: List[CurationFrame],
      settings: Settings): List[CurationReason] =
    val belowBest = for
      face <- frame.faces
      person <- face.personId
      best <- facesOf(person, others).map(_.quality).maxOption
      if best - face.quality >= settings.curationFaceQualityGap
    yield FaceQuality(person, face.quality, best)

    val sequenceSize = sequenceMates.size + 1
    val foundInMates = sequenceMates.flatMap(mate => personIds(mate).distinct)
    val missing = foundInMates.distinct.filterNot(personIds(frame).contains).flatMap {
      person =>
        val foundIn = foundInMates.count(_ == person)
        Option.when(foundIn * 2 >= sequenceSize)(PersonMissing(person, foundIn, sequenceSize))
    }

    belowBest ++ missing

  /**
   * A Person shared with another frame at a similar size, under the ratio of that Person's best at that size. A frame with no
   * such Person is compared on the whole frame against the sharpest of its Sequence, and a frame in no Sequence is not compared
   * at all.
   */
  private def blurry(
      frame: CurationFrame,
      others: List[CurationFrame],
      sequenceMates: List[CurationFrame],
      settings: Settings): List[CurationReason] =
    val ratio = settings.curationBlurRatio
    val comparablePeople = for
      face <- frame.faces
      person <- face.personId
      sharpness <- face.sharpness
      best <- facesOf(person, others).filter(isSimilarSize(face, _)).flatMap(_.sharpness).maxOption
    yield FaceSharpness(person, sharpness, best)

    if comparablePeople.nonEmpty then comparablePeople.filter(reason => reason.sharpness < ratio * reason.best)
    else
      (for
        quality <- frame.asset.imageQuality
        sharpest <- sequenceMates.flatMap(_.asset.imageQuality).map(_.sharpness).maxOption
        if quality.sharpness < ratio * sharpest
      yield FrameSharpness(quality.sharpness, sharpest)).toList

  private def absoluteDark(frame: CurationFrame, settings: Settings): List[Exposure] =
    exposureReadings(frame) {
      quality =>
        List(
          Option.when(quality.crushedShadows >= settings.curationCrushedFraction)(
            Exposure(ExposureMeasure.CrushedShadows, quality.crushedShadows, settings.curationCrushedFraction)),
          Option.when(quality.luminance < settings.curationDarkLuminance)(
            Exposure(ExposureMeasure.DarkLuminance, quality.luminance, settings.curationDarkLuminance))
        )
    }

  private def absoluteBright(frame: CurationFrame, settings: Settings): List[Exposure] =
    exposureReadings(frame) {
      quality =>
        List(
          Option.when(quality.clippedHighlights >= settings.curationClippedFraction)(
            Exposure(ExposureMeasure.ClippedHighlights, quality.clippedHighlights, settings.curationClippedFraction)),
          Option.when(quality.luminance > settings.curationBrightLuminance)(
            Exposure(ExposureMeasure.BrightLuminance, quality.luminance, settings.curationBrightLuminance))
        )
    }

  private def exposureReadings(frame: CurationFrame)(tests: ImageQuality => List[Option[Exposure]]): List[Exposure] =
    frame.asset.imageQuality.toList.flatMap(tests(_).flatten)

  /**
   * How much farther from the target the frame's luminance is than that of the frame of its Sequence nearest the target, past the
   * gap
   */
  private def luminanceGap(frame: CurationFrame, sequenceMates: List[CurationFrame], settings: Settings): Option[LuminanceGap] =
    for
      quality <- frame.asset.imageQuality
      nearest <- sequenceMates.flatMap(_.asset.imageQuality).map(_.luminance).minByOption(distanceFromTarget)
      if distanceFromTarget(quality.luminance) - distanceFromTarget(nearest) >= settings.curationLuminanceGap
    yield LuminanceGap(quality.luminance, nearest)

  private def distanceFromTarget(luminance: Double): Double = Math.abs(luminance - ImageMeasurement.TARGET_LUMINANCE)

  /**
   * The notice of one absolute problem, when it holds for every frame (an unmeasured frame has no readings, so it stops the
   * notice). A test every frame is past is the one to name, or else each test some frame is past, every frame being past one of
   * them.
   */
  private def noticeOf(kind: BundleKind, readingsPerFrame: List[List[Exposure]]): Option[CurationNotice] =
    Option.when(readingsPerFrame.nonEmpty && readingsPerFrame.forall(_.nonEmpty)) {
      val byMeasure = readingsPerFrame.flatten.groupBy(_.measure)
      val inEveryFrame = byMeasure.filter((_, readings) => readings.size == readingsPerFrame.size)
      val named = if inEveryFrame.nonEmpty then inEveryFrame else byMeasure
      CurationNotice(kind, named.values.map(mildest).toList.sortBy(_.measure.ordinal))
    }

  /**
   * The reading a notice can say of all its frames: a luminance under its limit is mildest at its highest, the others at their
   * lowest
   */
  private def mildest(readings: List[Exposure]): Exposure =
    if readings.head.measure == ExposureMeasure.DarkLuminance then readings.maxBy(_.value) else readings.minBy(_.value)

  private def facesOf(person: String, frames: List[CurationFrame]): List[Face] =
    frames.flatMap(_.faces).filter(_.personId.contains(person))

  private def personIds(frame: CurationFrame): List[String] = frame.faces.flatMap(_.personId)

  private def isSimilarSize(face: Face, other: Face): Boolean =
    val (area, otherArea) = (face.width.toLong * face.height, other.width.toLong * other.height)
    Math.max(area, otherArea) <= SIMILAR_SIZE_FACTOR * Math.min(area, otherArea)
