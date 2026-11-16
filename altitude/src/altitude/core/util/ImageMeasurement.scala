package altitude.core.util

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.img_hash.Img_hash
import org.opencv.imgproc.Imgproc

import altitude.core.ImageException
import altitude.core.models.Face
import altitude.core.models.Histogram
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash

/**
 * The Image quality, perceptual hash and Histogram of a still image, and the sharpness of its faces, as pure functions over a
 * decoded `Mat` (gray, BGR or BGRA). The image is first fitted into [[MEASUREMENT_BOX_SIZE]], so no metric depends on the
 * resolution. `ImageUtil.matFromBytes` applies the EXIF orientation, so face boxes are in upright pixels.
 */
object ImageMeasurement:

  case class Measurement(quality: ImageQuality, hash: PerceptualHash, faces: List[Face], histogram: Histogram)

  /**
   * The box the image is fitted into, never enlarged. It equals the default face detection box but stays a constant:
   * [[SHARPNESS_HALF_POINT]] is calibrated against it, so a Setting would silently rescale every later sharpness and score.
   */
  val MEASUREMENT_BOX_SIZE: Int = 1280

  /**
   * The sharpness at which the saturating term `s / (s + K)` is one half; past a few times this, the exposure decides the score.
   * Calibrated on the `people/` fixtures at the measurement box: sharp photos measure 90 to 410, their copies blurred with a
   * Gaussian of sigma 1 (a focus miss of about a pixel) 10 to 85, with sigma 2 under 10.
   */
  val SHARPNESS_HALF_POINT: Double = 30.0

  /**
   * The least area, in fitted-image pixels, for a box's sharpness to mean anything: 50 x 50, face detection's default smallest
   * face. Under it a Face has no sharpness, and a smaller union of face boxes makes the score read the whole frame, so one tiny
   * face does not decide it.
   */
  val MIN_FACE_AREA_PX: Int = 2500

  /** A pixel at or above this gray level is a clipped highlight; at or below [[CRUSHED_LEVEL]], a crushed shadow */
  val CLIPPED_LEVEL: Int = 250
  val CRUSHED_LEVEL: Int = 5

  /** The exposure term's weights: a fraction of clipped or crushed pixels costs twice itself, a mean gray off this target once */
  val CLIPPED_WEIGHT: Double = 2.0
  val CRUSHED_WEIGHT: Double = 2.0
  val TARGET_LUMINANCE: Double = 0.45

  /** The faces' boxes are in the image's own pixels, before fitting */
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
      Measurement(qualityOf(gray, laplacian, boxes), hash(scaled), measuredFaces.toList, histogramOf(scaled, gray))
    finally
      gray.release()
      laplacian.release()
      if scaled ne image then scaled.release()

  /**
   * 0 to 100: the sharpness term times the exposure term. [[qualityOf]] passes the faces' sharpness when they are large enough,
   * so two scores do not compare when only one image's faces are.
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

  /**
   * Luminance over `gray`, the pixels the clipped and crushed fractions count; colors over the planes, which OpenCV orders blue,
   * green, red, alpha left out. A gray image's colors are its luminance.
   */
  private def histogramOf(image: Mat, gray: Mat): Histogram =
    val luminance = binsOf(gray, plane = 0)
    if image.channels() == 1 then Histogram(luminance, red = luminance, green = luminance, blue = luminance)
    else Histogram(luminance, red = binsOf(image, plane = 2), green = binsOf(image, plane = 1), blue = binsOf(image, plane = 0))

  /** Each bin as the fraction of the image's pixels that fell in it */
  private def binsOf(image: Mat, plane: Int): IArray[Float] =
    val counts = new Mat()
    val everyPixel = new Mat()
    val planes = new MatOfInt(plane)
    val bins = new MatOfInt(Histogram.BINS)
    val levels = new MatOfFloat(0f, 256f)
    try
      Imgproc.calcHist(java.util.List.of(image), planes, everyPixel, counts, bins, levels)
      val counted = new Array[Float](Histogram.BINS)
      counts.get(0, 0, counted)
      val pixels = image.rows().toDouble * image.cols()
      IArray.unsafeFromArray(counted.map(count => (count / pixels).toFloat))
    finally List(counts, everyPixel, planes, bins, levels).foreach(_.release())

  private def toGray(image: Mat, gray: Mat): Unit =
    image.channels() match
      case 1 => image.copyTo(gray)
      case 3 => Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
      case 4 => Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
      case channels => throw ImageException(s"An image of $channels channels cannot be measured")

  private def fitScale(image: Mat): Double =
    Math.min(1.0, ImageUtil.determineImageScale(image.width(), image.height(), MEASUREMENT_BOX_SIZE, MEASUREMENT_BOX_SIZE))

  /** `INTER_AREA` averages the pixels it drops. At a scale of 1 this is the image itself, which the caller must not release */
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

  /** Clamped to the scaled image */
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
   * The sharpness over the union of the face boxes, which the score reads so a sharp portrait on a flat sky is not dragged down.
   * Not stored: its region varies from image to image, so two values do not compare.
   */
  private def sharpnessInFaces(laplacian: Mat, faceBoxes: Seq[Rect]): Option[Double] =
    val mask = new Mat()
    try
      fillMask(mask, laplacian, faceBoxes)
      Option.when(Core.countNonZero(mask) >= MIN_FACE_AREA_PX)(varianceOf(laplacian, mask))
    finally mask.release()

  private def sharpnessInBox(laplacian: Mat, box: Rect): Option[Double] =
    Option.when(box.area() >= MIN_FACE_AREA_PX) {
      val region = laplacian.submat(box)
      try varianceOf(region)
      finally region.release()
    }

  /** Over the pixels the mask sets; an empty mask means every pixel */
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

  /** Overlapping boxes count once */
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

  /** `THRESH_BINARY` keeps the pixels above the level, `THRESH_BINARY_INV` those at or below */
  private def fractionWhere(gray: Mat, level: Int, thresholdType: Int): Double =
    val kept = new Mat()
    try
      Imgproc.threshold(gray, kept, level, 255, thresholdType)
      Core.countNonZero(kept).toDouble / (gray.rows().toLong * gray.cols())
    finally kept.release()
