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
 * Sorts the frames of a Curation set into Bundles, notices and Contenders. Pure, and run when a set is read: nothing is stored,
 * so a changed threshold applies from the next read.
 *
 * Relative tests compare only what is comparable: sharpness and exposure across scenes mostly reflect content, so whole-frame and
 * luminance comparisons stay within one Sequence, while same-Person tests run across the set. Faces are never compared across
 * Persons, and the Image quality's score is never read.
 */
object CurationRule:

  /** One Person's Faces compare on sharpness only within this factor of box area: sharpness falls as a face fills the frame */
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
   * Capture order, undated frames last, then import time and ID, so the order is stable and an undated Sequence shows as uploaded
   * rather than by random IDs. `AssetDao.sequenceFramesStatement` must sort the same way, since it cuts a window.
   */
  private val captureOrder: Ordering[CurationFrame] =
    given Ordering[LocalDateTime] = Ordering.fromLessThan(_.isBefore(_))
    Ordering.by(
      frame => (frame.asset.originalCreatedAt.isEmpty, frame.asset.originalCreatedAt, frame.asset.createdAt, frame.asset.id))

  /**
   * A Face at least the gap under the same Person's best, or, in a Sequence, a Person in at least half of its frames missing
   * here. The best comes from the other frames only: a Person twice in this one (twins) is nothing a Pick can tell apart.
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
   * A Person under the ratio of their sharpest similar-size Face in another frame. A frame with no such Person is compared whole
   * against the sharpest of its Sequence, and not at all outside one.
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

  /** The frame's luminance when it is at least the gap farther from the target than its Sequence's nearest frame */
  private def luminanceGap(frame: CurationFrame, sequenceMates: List[CurationFrame], settings: Settings): Option[LuminanceGap] =
    for
      quality <- frame.asset.imageQuality
      nearest <- sequenceMates.flatMap(_.asset.imageQuality).map(_.luminance).minByOption(distanceFromTarget)
      if distanceFromTarget(quality.luminance) - distanceFromTarget(nearest) >= settings.curationLuminanceGap
    yield LuminanceGap(quality.luminance, nearest)

  private def distanceFromTarget(luminance: Double): Double = Math.abs(luminance - ImageMeasurement.TARGET_LUMINANCE)

  /**
   * The notice of one absolute problem, when every frame has it (an unmeasured frame has no readings, so it stops the notice). It
   * names the tests every frame fails, or else every test some frame fails.
   */
  private def noticeOf(kind: BundleKind, readingsPerFrame: List[List[Exposure]]): Option[CurationNotice] =
    Option.when(readingsPerFrame.nonEmpty && readingsPerFrame.forall(_.nonEmpty)) {
      val byMeasure = readingsPerFrame.flatten.groupBy(_.measure)
      val inEveryFrame = byMeasure.filter((_, readings) => readings.size == readingsPerFrame.size)
      val named = if inEveryFrame.nonEmpty then inEveryFrame else byMeasure
      CurationNotice(kind, named.values.map(mildest).toList.sortBy(_.measure.ordinal))
    }

  /** The reading true of all a notice's frames: a dark luminance is mildest at its highest, the others at their lowest */
  private def mildest(readings: List[Exposure]): Exposure =
    if readings.head.measure == ExposureMeasure.DarkLuminance then readings.maxBy(_.value) else readings.minBy(_.value)

  private def facesOf(person: String, frames: List[CurationFrame]): List[Face] =
    frames.flatMap(_.faces).filter(_.personId.contains(person))

  private def personIds(frame: CurationFrame): List[String] = frame.faces.flatMap(_.personId)

  private def isSimilarSize(face: Face, other: Face): Boolean =
    val (area, otherArea) = (face.width.toLong * face.height, other.width.toLong * other.height)
    Math.max(area, otherArea) <= SIMILAR_SIZE_FACTOR * Math.min(area, otherArea)
