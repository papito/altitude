package altitude.core.util

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.img_hash.Img_hash
import org.opencv.imgproc.Imgproc

import altitude.core.ImageException
import altitude.core.models.Face
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash

/**
 * The Image quality and the perceptual hash of a still image, and the sharpness of its faces: pure functions over a decoded `Mat`
 * (gray, BGR or BGRA, as `ImageUtil` decodes) and its faces. The image is first scaled to fit [[MEASUREMENT_BOX_SIZE]], so
 * neither the sharpness nor the hash depends on the resolution. `ImageUtil.matFromBytes` applies the EXIF orientation as it
 * decodes, so the image is upright and a face's box is in upright pixels.
 */
object ImageMeasurement:

  /** What measuring an image gives: its Image quality, its hash, and its faces in the order given, each with its sharpness */
  case class Measurement(quality: ImageQuality, hash: PerceptualHash, faces: List[Face])

  /**
   * The box the image is fitted into before it is measured, never enlarged. It is the default face detection box, but a constant
   * of its own: [[SHARPNESS_HALF_POINT]] is calibrated against it, and a Setting could not change it without silently rescaling
   * every later sharpness and score.
   */
  val MEASUREMENT_BOX_SIZE: Int = 1280

  /**
   * The sharpness at which the sharpness term is one half: `s / (s + K)` saturates, so a value past a few times this counts as
   * sharp and the score is then decided by the exposure. Calibrated on the `people/` fixtures, fitted to the measurement box: the
   * sharp photos measure 90 to 410, their copies blurred with a Gaussian of sigma 1 (a focus miss of about a pixel) 10 to 85,
   * with sigma 2 under 10. This is about the sharpness of a photo softened by a pixel. A change here is a code change, not a
   * tuning.
   */
  val SHARPNESS_HALF_POINT: Double = 30.0

  /**
   * The least area, in pixels of the fitted image, a box must cover for its sharpness to mean anything: a 50 x 50 box, face
   * detection's smallest face at the box scale. Under it a Face has no sharpness, and when the union of an image's face boxes is
   * under it the score reads the whole frame's, so one tiny face does not decide the score.
   */
  val MIN_FACE_AREA_PX: Int = 2500

  /** The gray level from which a pixel counts as a clipped highlight, and up to which it counts as a crushed shadow */
  val CLIPPED_LEVEL: Int = 250
  val CRUSHED_LEVEL: Int = 5

  /** The exposure term's weights: a fraction of clipped or crushed pixels costs twice itself, a mean gray off this target once */
  val CLIPPED_WEIGHT: Double = 2.0
  val CRUSHED_WEIGHT: Double = 2.0
  val TARGET_LUMINANCE: Double = 0.45

  /** The metrics, the score, the hash and each face's sharpness; the faces' boxes are in the image's own pixels */
  def measure(image: Mat, faces: Seq[Face]): Measurement =
    val scale = fitScale(image)
    val scaled = fitToBox(image, scale)
    val gray = new Mat()
    val laplacian = new Mat()
    try
      toGray(scaled, gray)
      Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
      val boxes = faces.map(face => scaleBox(face, scale, gray))
      val measuredFaces = faces.zip(boxes).map((face, box) => face.copy(sharpness = sharpnessInBox(laplacian, box)))
      Measurement(qualityOf(gray, laplacian, boxes), hash(scaled), measuredFaces.toList)
    finally
      gray.release()
      laplacian.release()
      if scaled ne image then scaled.release()

  /**
   * The 0 to 100 score of the metrics: the saturating sharpness term times the exposure term. `sharpness` is the one
   * [[qualityOf]] reads for the score, of the faces when they are large enough, so two scores do not compare when only one
   * image's faces are.
   */
  def score(sharpness: Double, luminance: Double, clippedHighlights: Double, crushedShadows: Double): Int =
    val sharpnessTerm = sharpness / (sharpness + SHARPNESS_HALF_POINT)
    val exposurePenalty =
      CLIPPED_WEIGHT * clippedHighlights + CRUSHED_WEIGHT * crushedShadows + Math.abs(luminance - TARGET_LUMINANCE)
    val exposureTerm = 1 - Math.min(1.0, exposurePenalty)
    Math.round(100 * sharpnessTerm * exposureTerm).toInt

  /** OpenCV's pHash, the DCT hash of a 32 x 32 downscale: eight bytes, read as one big-endian Long */
  def hash(image: Mat): PerceptualHash =
    val out = new Mat()
    try
      Img_hash.pHash(image, out)
      val bytes = new Array[Byte](PerceptualHash.BITS / 8)
      out.get(0, 0, bytes)
      PerceptualHash(bytes.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL)))
    finally out.release()

  /** The image as one gray channel, whatever `ImageUtil` decoded it as */
  private def toGray(image: Mat, gray: Mat): Unit =
    image.channels() match
      case 1 => image.copyTo(gray)
      case 3 => Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
      case 4 => Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
      case channels => throw ImageException(s"An image of $channels channels cannot be measured")

  /** The factor that fits the image in the measurement box, never enlarging it */
  private def fitScale(image: Mat): Double =
    Math.min(1.0, ImageUtil.determineImageScale(image.width(), image.height(), MEASUREMENT_BOX_SIZE, MEASUREMENT_BOX_SIZE))

  /** The image scaled down by `scale` (`INTER_AREA`, which averages the pixels it drops), or the image itself at a scale of 1 */
  private def fitToBox(image: Mat, scale: Double): Mat =
    if scale >= 1.0 then image
    else
      val scaled = new Mat()
      try Imgproc.resize(image, scaled, new Size(), scale, scale, Imgproc.INTER_AREA)
      catch
        case e: Throwable =>
          scaled.release()
          throw e
      scaled

  /** A face's box in the scaled image's pixels, clamped to it */
  private def scaleBox(face: Face, scale: Double, image: Mat): Rect =
    val x1 = Math.max(0, Math.floor(face.x1 * scale).toInt)
    val y1 = Math.max(0, Math.floor(face.y1 * scale).toInt)
    val x2 = Math.min(image.cols(), Math.ceil((face.x1 + face.width) * scale).toInt)
    val y2 = Math.min(image.rows(), Math.ceil((face.y1 + face.height) * scale).toInt)
    new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1))

  private def qualityOf(gray: Mat, laplacian: Mat, faceBoxes: Seq[Rect]): ImageQuality =
    val frameSharpness = varianceOf(laplacian)
    val luminance = Core.mean(gray).`val`(0) / 255.0
    val clipped = fractionAbove(gray, CLIPPED_LEVEL - 1)
    val crushed = fractionAtMost(gray, CRUSHED_LEVEL)
    val subjectSharpness = sharpnessInFaces(laplacian, faceBoxes).getOrElse(frameSharpness)
    ImageQuality(frameSharpness, luminance, clipped, crushed, score(subjectSharpness, luminance, clipped, crushed))

  /**
   * The variance of the Laplacian over the pixels inside the union of the face boxes when they cover at least
   * [[MIN_FACE_AREA_PX]]: what the score reads, so a sharp portrait on a flat sky is not dragged down. Not stored: its region
   * varies from image to image, so two values of it do not compare.
   */
  private def sharpnessInFaces(laplacian: Mat, faceBoxes: Seq[Rect]): Option[Double] =
    val mask = new Mat()
    try
      fillMask(mask, laplacian, faceBoxes)
      Option.when(Core.countNonZero(mask) >= MIN_FACE_AREA_PX)(varianceOf(laplacian, mask))
    finally mask.release()

  /** The variance of the Laplacian inside one box, when it covers at least [[MIN_FACE_AREA_PX]] */
  private def sharpnessInBox(laplacian: Mat, box: Rect): Option[Double] =
    Option.when(box.area() >= MIN_FACE_AREA_PX) {
      val region = laplacian.submat(box)
      try varianceOf(region)
      finally region.release()
    }

  /** The variance of an image's values, over the pixels a mask sets; an empty mask is every pixel */
  private def varianceOf(values: Mat, mask: Mat = new Mat()): Double =
    val mean = new MatOfDouble()
    val stdDev = new MatOfDouble()
    try
      Core.meanStdDev(values, mean, stdDev, mask)
      val sd = stdDev.toArray()(0)
      sd * sd
    finally
      mean.release()
      stdDev.release()

  /** Sets the mask to the image's size, set inside the boxes and nowhere else; overlapping boxes count once */
  private def fillMask(mask: Mat, image: Mat, boxes: Seq[Rect]): Unit =
    mask.create(image.size(), CvType.CV_8UC1)
    mask.setTo(new Scalar(0))
    boxes
      .filter(box => box.width > 0 && box.height > 0)
      .foreach(box => Imgproc.rectangle(mask, box, new Scalar(255), Imgproc.FILLED))

  private def fractionAbove(gray: Mat, level: Int): Double =
    fractionWhere(gray, level, Imgproc.THRESH_BINARY)

  private def fractionAtMost(gray: Mat, level: Int): Double =
    fractionWhere(gray, level, Imgproc.THRESH_BINARY_INV)

  /** The fraction of pixels a threshold keeps: `THRESH_BINARY` those above the level, `THRESH_BINARY_INV` those at or below */
  private def fractionWhere(gray: Mat, level: Int, thresholdType: Int): Double =
    val kept = new Mat()
    try
      Imgproc.threshold(gray, kept, level, 255, thresholdType)
      Core.countNonZero(kept).toDouble / (gray.rows().toLong * gray.cols())
    finally kept.release()
