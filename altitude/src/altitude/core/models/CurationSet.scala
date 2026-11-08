package altitude.core.models

/**
 * A frame of a Curation set, as read for the rule: its asset, its Faces (those of hidden and bad-match people left out) and
 * whether it is in any album or Location, which recycling would take it out of. Its Histogram is for the view alone, which draws
 * it under the frame on the stage; the rule reads none of it, and a frame not measured has none.
 */
case class CurationFrame(asset: Asset, faces: List[Face], inAlbumOrLocation: Boolean, histogram: Option[Histogram] = None):

  /** A still image the pipeline did not measure has no metrics: it is a Contender, and the view notes why */
  def isMeasured: Boolean = asset.imageQuality.isDefined

/** The problems a frame can be bundled under, most serious first: a frame sits under the first that holds for it */
enum BundleKind:
  case PoorFaces, Blurry, TooDark, TooBright

/** An absolute exposure test: the metric read and the Setting it is held against */
enum ExposureMeasure:
  case CrushedShadows, DarkLuminance, ClippedHighlights, BrightLuminance

/** Why a frame has a problem: what is wrong, how much, and compared to what, for the view to render */
sealed trait CurationReason

object CurationReason:
  /** One of the frame's Faces against the best quality of the same Person in the set's other frames */
  case class FaceQuality(personId: String, quality: Double, best: Double) extends CurationReason

  /** A Person with no Face in this frame, found in `foundIn` of the `frames` frames of its Sequence in the set */
  case class PersonMissing(personId: String, foundIn: Int, frames: Int) extends CurationReason

  /** One of the frame's Faces against the sharpest Face of the same Person at a similar size in the set's other frames */
  case class FaceSharpness(personId: String, sharpness: Double, best: Double) extends CurationReason

  /** The whole frame against the sharpest other frame of its Sequence in the set */
  case class FrameSharpness(sharpness: Double, sharpest: Double) extends CurationReason

  /** A metric past the limit its Setting gives */
  case class Exposure(measure: ExposureMeasure, value: Double, limit: Double) extends CurationReason

  /**
   * The frame's luminance against that of the frame of its Sequence nearest `ImageMeasurement.TARGET_LUMINANCE`. The problem
   * holding it says which side of the target the frame is on.
   */
  case class LuminanceGap(luminance: Double, nearest: Double) extends CurationReason

/** One problem of a frame with its reasons. A frame's problems other than the one that bundles it show as chips. */
case class CurationProblem(kind: BundleKind, reasons: List[CurationReason])

/** A frame under its most serious problem: the reasons for that problem, and its other problems in priority order */
case class BundledFrame(frame: CurationFrame, reasons: List[CurationReason], chips: List[CurationProblem])

/** The frames of a Curation set whose main problem is the same, in capture order */
case class CurationBundle(kind: BundleKind, frames: List[BundledFrame])

/**
 * An absolute Too dark or Too bright that holds for every frame, which no Pick can fix. The readings are the tests every frame is
 * past when there are any, or else each test some frame is past, every frame being past one of them. Each reading is the mildest
 * value among the frames past that test, so "Every frame: 8% blown highlights" holds for all of them.
 */
case class CurationNotice(kind: BundleKind, readings: List[CurationReason.Exposure])

/** A row of the face strip: a Person of the set, and the Face it shows in each frame holding one, by the frame's asset ID */
case class CurationFaceRow(personId: String, faces: Map[String, Face])

/**
 * What `CurationRule` decides for a Curation set. Nothing here is stored.
 *
 * @param frames
 *   every frame, in capture order
 * @param notices
 *   the problems of every frame, Too dark before Too bright
 * @param bundles
 *   the Bundles that hold a frame, in priority order
 * @param contenders
 *   the frames in no Bundle, in capture order
 * @param heldBack
 *   how many frames of a Sequence are past the window a set reads, which `CurationService` knows and the rule does not: the set
 *   holds part of the Sequence, whose Picks stay in it and which stays in the queue
 */
case class CurationSet(
    frames: List[CurationFrame],
    notices: List[CurationNotice],
    bundles: List[CurationBundle],
    contenders: List[CurationFrame],
    heldBack: Int = 0):

  /** With no Bundle, the measurements cannot separate the frames, and the view suggests nothing */
  def isTooCloseToCall: Boolean = bundles.isEmpty

  /** The one suggestion the rule makes, which the view marks: the only frame with no problem, when others have one */
  def soleContender: Option[CurationFrame] = contenders match
    case List(only) if bundles.nonEmpty => Some(only)
    case _ => None

  /** The frames in the order of the Bundle rail, which the face strip's columns and the view's arrow keys follow */
  def railFrames: List[CurationFrame] = contenders ++ bundles.flatMap(_.frames.map(_.frame))

  /**
   * What the stage opens on, the pinned frame and the frame focused: the rail's first two frames, so two Contenders when the set
   * has them, a sole Contender beside the first frame of a Bundle, or the first two frames of the Bundles. A set of one frame
   * opens on it alone.
   */
  def openingFrames: (Option[CurationFrame], CurationFrame) = railFrames match
    case first :: second :: _ => (Some(first), second)
    case only => (None, only.head)

  /**
   * The rows of the face strip, the Persons found in the most frames first, the others as the rail first shows them. A Person
   * twice in one frame shows the better Face.
   */
  def faceRows: List[CurationFaceRow] =
    val placed = for
      frame <- railFrames
      face <- frame.faces
      personId <- face.personId
    yield (personId, frame.asset.persistedId, face)

    placed
      .groupBy(_._1)
      .toList
      .sortBy((personId, faces) => (-faces.map(_._2).distinct.size, placed.indexWhere(_._1 == personId)))
      .map((personId, faces) => CurationFaceRow(personId, faces.groupMap(_._2)(_._3).view.mapValues(_.maxBy(_.quality)).toMap))
