package altitude.core.unit

import altitude.test.TestFocus
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.ImageException
import altitude.core.models.Face
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash
import altitude.core.util.ImageMeasurement

/**
 * The Image quality metrics, the score and the perceptual hash of [[ImageMeasurement]], as orderings on the fixtures: the
 * `people/` photos, since the `images/` ones are 10 to 64 px and too small to be sharp or soft
 */
@DoNotDiscover class ImageMeasurementTests extends AnyFunSuite with TestFocus {

  Loader.load(classOf[opencv_java])

  /** The default `sequence.max_hash_distance`: the bits two frames of one burst may differ in */
  private val NEAR = 10

  private def fixture(relPath: String): Mat =
    val image = Imgcodecs.imread(getClass.getResource(s"/import/$relPath").getPath, Imgcodecs.IMREAD_COLOR)
    if image.empty() then fail(s"Could not read $relPath")
    image

  private def blurred(image: Mat, sigma: Double): Mat =
    val out = new Mat()
    Imgproc.GaussianBlur(image, out, new Size(0, 0), sigma)
    out

  /** Runs `f` over a flat image of one gray level and releases it */
  private def withFlat[A](gray: Int, channels: Int = 3)(f: Mat => A): A =
    val image = new Mat(120, 160, CvType.CV_8UC(channels), new Scalar(gray, gray, gray, 255))
    try f(image)
    finally image.release()

  /** A Face whose box is the rectangle; the rest of it does not matter to the measurement */
  private def faceAt(box: Rect): Face =
    Face(
      x1 = box.x,
      y1 = box.y,
      width = box.width,
      height = box.height,
      detectionScore = 0.9,
      checksum = "",
      features = Array.empty,
      quality = 20.0,
      isEnrolled = true)

  private def measure(image: Mat, boxes: Seq[Rect] = Nil): (ImageQuality, PerceptualHash) =
    val measurement = ImageMeasurement.measure(image, boxes.map(faceAt))
    (measurement.quality, measurement.hash)

  /** The sharpness measured for each box, in the order given */
  private def faceSharpness(image: Mat, boxes: Seq[Rect]): List[Option[Double]] =
    ImageMeasurement.measure(image, boxes.map(faceAt)).faces.map(_.sharpness)

  /**
   * Runs `f` over `people/affleck.jpg` blurred everywhere except a rectangle in the middle, which keeps the original pixels, with
   * that rectangle, a box over a blurred corner, and a 30 x 30 box inside the sharp rectangle, under the least face area that
   * counts
   */
  private def withPartlySharpPhoto[A](f: (Mat, Rect, Rect, Rect) => A): A =
    val image = fixture("people/affleck.jpg")
    val composite = blurred(image, 3.0)
    val sharpRect = new Rect(image.cols() / 4, image.rows() / 4, image.cols() / 2, image.rows() / 2)
    val sharpSource = image.submat(sharpRect)
    val sharpTarget = composite.submat(sharpRect)
    sharpSource.copyTo(sharpTarget)
    sharpSource.release()
    sharpTarget.release()
    val blurredCorner = new Rect(0, 0, image.cols() / 5, image.rows() / 5)
    val tiny = new Rect(image.cols() / 2, image.rows() / 2, 30, 30)
    try f(composite, sharpRect, blurredCorner, tiny)
    finally List(image, composite).foreach(_.release())

  private def quality(image: Mat, boxes: Seq[Rect] = Nil): ImageQuality = measure(image, boxes)._1

  private def hash(image: Mat): PerceptualHash = measure(image)._2

  test("A sharp photo measures sharper, and scores higher, than its blurred copies") {

    /**
     * Setup:
     *
     * `people/affleck.jpg` and copies of it blurred with a Gaussian of sigma 1 and sigma 2, measured with no face boxes.
     *
     * Assertions:
     *
     * The sharpness and the score fall with each step of blur; the exposure metrics stay nearly the same, since blurring moves no
     * light; every score is within 0 to 100.
     */
    val sharp = fixture("people/affleck.jpg")
    val soft = blurred(sharp, 1.0)
    val softer = blurred(sharp, 2.0)
    val (sharpQuality, softQuality, softerQuality) = (quality(sharp), quality(soft), quality(softer))

    sharpQuality.sharpness should be > softQuality.sharpness
    softQuality.sharpness should be > softerQuality.sharpness
    sharpQuality.score should be > softQuality.score
    softQuality.score should be > softerQuality.score
    softQuality.luminance shouldBe sharpQuality.luminance +- 0.01
    softQuality.clippedHighlights shouldBe sharpQuality.clippedHighlights +- 0.01
    all(List(sharpQuality.score, softQuality.score, softerQuality.score)) should ((be >= 0).and(be <= 100))
    List(sharp, soft, softer).foreach(_.release())
  }

  test("A flat white image clips, a flat black one crushes, and a mid-gray does neither") {

    /**
     * Setup:
     *
     * Three flat images: white (255), black (0) and mid-gray (128).
     *
     * Assertions:
     *
     * Every pixel of the white one is a clipped highlight and none a crushed shadow, with a luminance of 1; the black one the
     * reverse, with a luminance of 0; the gray one has neither and a luminance of about a half. A flat image has no edges, so its
     * sharpness and score are 0.
     */
    val white = withFlat(255)(quality(_))
    white.clippedHighlights shouldBe 1.0
    white.crushedShadows shouldBe 0.0
    white.luminance shouldBe 1.0 +- 1e-9

    val black = withFlat(0)(quality(_))
    black.clippedHighlights shouldBe 0.0
    black.crushedShadows shouldBe 1.0
    black.luminance shouldBe 0.0 +- 1e-9

    val gray = withFlat(128)(quality(_))
    gray.clippedHighlights shouldBe 0.0
    gray.crushedShadows shouldBe 0.0
    gray.luminance shouldBe 0.5 +- 0.01

    all(List(white, black, gray).map(_.sharpness)) shouldBe 0.0
    all(List(white, black, gray).map(_.score)) shouldBe 0
  }

  test("A gray, a BGR and a BGRA image of the same picture measure alike; any other channel count is refused") {

    /**
     * Setup:
     *
     * `people/damon.jpg` as decoded (BGR), the same converted to one gray channel (as `IMREAD_ANYCOLOR` decodes a grayscale file)
     * and to BGRA, and a two-channel image.
     *
     * Assertions:
     *
     * The three layouts give the same sharpness, luminance and hash within rounding, since all are measured over the same gray
     * pixels; the two-channel image is an `ImageException`.
     */
    val bgr = fixture("people/damon.jpg")
    val gray = new Mat()
    val bgra = new Mat()
    Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
    Imgproc.cvtColor(bgr, bgra, Imgproc.COLOR_BGR2BGRA)

    val (fromBgr, hashBgr) = measure(bgr)
    val (fromGray, hashGray) = measure(gray)
    val (fromBgra, hashBgra) = measure(bgra)
    fromGray.sharpness shouldBe fromBgr.sharpness +- fromBgr.sharpness / 100
    fromBgra.sharpness shouldBe fromBgr.sharpness +- fromBgr.sharpness / 100
    fromGray.luminance shouldBe fromBgr.luminance +- 0.01
    fromBgra.luminance shouldBe fromBgr.luminance +- 0.01
    hashBgr.distance(hashGray) should be <= NEAR
    hashBgr.distance(hashBgra) should be <= NEAR

    withFlat(128, channels = 2)(image => an[ImageException] should be thrownBy measure(image))
    List(bgr, gray, bgra).foreach(_.release())
  }

  test("A well exposed photo scores above the same photo blown out or crushed") {

    /**
     * Setup:
     *
     * `people/damon.jpg` (nothing clipped or crushed), the same image with 160 added to every channel (saturating to white) and
     * the same with 160 taken from every channel (saturating to black).
     *
     * Assertions:
     *
     * The blown-out copy has more clipped highlights, the crushed copy more crushed shadows, and both score below the original.
     */
    val image = fixture("people/damon.jpg")
    val blown = new Mat()
    val crushed = new Mat()
    Core.add(image, new Scalar(160, 160, 160), blown)
    Core.subtract(image, new Scalar(160, 160, 160), crushed)

    val (original, blownQuality, crushedQuality) = (quality(image), quality(blown), quality(crushed))
    blownQuality.clippedHighlights should be > original.clippedHighlights
    crushedQuality.crushedShadows should be > original.crushedShadows
    blownQuality.score should be < original.score
    crushedQuality.score should be < original.score
    List(image, blown, crushed).foreach(_.release())
  }

  test("An image's sharpness is the whole frame's, whatever faces it has") {

    /**
     * Setup:
     *
     * A photo sharp only in its middle (`withPartlySharpPhoto`), measured with no faces, with a face over the sharp rectangle,
     * with one over a blurred corner, and with one too small to count.
     *
     * Assertions:
     *
     * All four have one sharpness, so two images can be compared on it whichever faces were detected in each. The exposure
     * metrics are the whole frame's too.
     */
    withPartlySharpPhoto {
      (photo, sharpRect, blurredCorner, tiny) =>
        val whole = quality(photo)
        List(sharpRect, blurredCorner, tiny).map(box => quality(photo, Seq(box))).foreach {
          withFace =>
            withFace.sharpness shouldBe whole.sharpness
            withFace.luminance shouldBe whole.luminance
            withFace.clippedHighlights shouldBe whole.clippedHighlights
        }
    }
  }

  test("The faces decide the score, unless they are too small to count") {

    /**
     * Setup:
     *
     * The same photo, scored with no faces, with a face over the sharp rectangle, with one over a blurred corner, and with one
     * too small to count.
     *
     * Assertions:
     *
     * The face over the sharp rectangle scores above the whole frame, which scores above the face over the blurred corner, so a
     * sharp portrait on a flat background is not dragged down. The tiny face scores exactly as the whole frame.
     */
    withPartlySharpPhoto {
      (photo, sharpRect, blurredCorner, tiny) =>
        val whole = quality(photo)
        quality(photo, Seq(sharpRect)).score should be > whole.score
        whole.score should be > quality(photo, Seq(blurredCorner)).score
        quality(photo, Seq(tiny)).score shouldBe whole.score
    }
  }

  test("Each face has its own sharpness, unless it is too small to count") {

    /**
     * Setup:
     *
     * The same photo, measured with a sharp face, a blurred face and a face too small to count, in that order, then in the
     * reverse order, and with no faces.
     *
     * Assertions:
     *
     * The sharp face measures above the blurred one, so a soft face in a group shot is not averaged away. The tiny face has none.
     * The values follow the faces' order, and no faces give none.
     */
    withPartlySharpPhoto {
      (photo, sharpRect, blurredCorner, tiny) =>
        val measured = faceSharpness(photo, Seq(sharpRect, blurredCorner, tiny))
        measured(0).value should be > measured(1).value
        measured(2) shouldBe None
        faceSharpness(photo, Seq(tiny, blurredCorner, sharpRect)) shouldBe measured.reverse
        faceSharpness(photo, Nil) shouldBe Nil
    }
  }

  test("A face's box is clamped to the image") {

    /**
     * Setup:
     *
     * A photo, measured with a box reaching past its bottom right corner and with the part of that box inside the image.
     *
     * Assertions:
     *
     * Both measure the same, and neither throws.
     */
    val image = fixture("people/affleck.jpg")
    val inside = new Rect(image.cols() - 100, image.rows() - 100, 100, 100)
    val past = new Rect(image.cols() - 100, image.rows() - 100, 200, 200)
    faceSharpness(image, Seq(past)) shouldBe faceSharpness(image, Seq(inside))
    faceSharpness(image, Seq(inside)).head should not be None
    image.release()
  }

  test("The score follows the formula over the metrics") {

    /**
     * Setup:
     *
     * The score function alone, with metrics chosen by hand.
     *
     * Assertions:
     *
     * At the half-point sharpness and a perfect exposure the score is 50; a very sharp image with a perfect exposure comes to 99
     * or more; the exposure penalty saturates, so a fully clipped image scores 0 whatever its sharpness; a luminance off the
     * target costs the difference; an image without edges scores 0.
     */
    val K = ImageMeasurement.SHARPNESS_HALF_POINT
    ImageMeasurement.score(K, ImageMeasurement.TARGET_LUMINANCE, 0, 0) shouldBe 50
    ImageMeasurement.score(K * 1000, ImageMeasurement.TARGET_LUMINANCE, 0, 0) should be >= 99
    ImageMeasurement.score(K * 1000, 1.0, clippedHighlights = 1.0, crushedShadows = 0) shouldBe 0
    ImageMeasurement.score(K * 1000, ImageMeasurement.TARGET_LUMINANCE + 0.25, 0, 0) shouldBe (75 +- 1)
    ImageMeasurement.score(0, ImageMeasurement.TARGET_LUMINANCE, 0, 0) shouldBe 0
  }

  test("The perceptual hash is near for a shifted, brightened copy and far for another photo") {

    /**
     * Setup:
     *
     * `people/affleck.jpg`; a copy of it shifted by three pixels each way (the edge filled by its border) and brightened by 12
     * levels, as the next frame of a burst would differ; the same image at half its size; and `people/damon.jpg`, a different
     * picture.
     *
     * Assertions:
     *
     * The hash is deterministic. The burst-like copy and the half-size copy are within the default `sequence.max_hash_distance`
     * of the original, the different picture is farther than that, and the distance is symmetric.
     */
    val image = fixture("people/affleck.jpg")
    val shifted = new Mat()
    val shift = Mat.eye(2, 3, CvType.CV_64F)
    shift.put(0, 2, 3.0)
    shift.put(1, 2, 3.0)
    Imgproc.warpAffine(image, shifted, shift, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
    Core.add(shifted, new Scalar(12, 12, 12), shifted)
    val half = new Mat()
    Imgproc.resize(image, half, new Size(), 0.5, 0.5, Imgproc.INTER_AREA)
    val other = fixture("people/damon.jpg")

    val original = hash(image)
    hash(image) shouldBe original
    original.distance(hash(shifted)) should be <= NEAR
    original.distance(hash(half)) should be <= NEAR
    original.distance(hash(other)) should be > NEAR
    hash(other).distance(original) shouldBe original.distance(hash(other))
    List(image, shifted, shift, half, other).foreach(_.release())
  }

  test("A larger copy of a photo measures as the photo, so the resolution does not decide the sharpness") {

    /**
     * Setup:
     *
     * `people/affleck.jpg` (1280 x 1786, so it is fitted to the box already) and the same photo enlarged twice.
     *
     * Assertions:
     *
     * The two sharpness values are within a third of each other and the hashes within the burst distance: both are measured over
     * the image fitted to the box.
     */
    val image = fixture("people/affleck.jpg")
    val large = new Mat()
    Imgproc.resize(image, large, new Size(), 2.0, 2.0, Imgproc.INTER_CUBIC)

    val (fittedQuality, fittedHash) = measure(image)
    val (largeQuality, largeHash) = measure(large)
    largeQuality.sharpness shouldBe fittedQuality.sharpness +- fittedQuality.sharpness / 3
    fittedHash.distance(largeHash) should be <= NEAR
    image.release()
    large.release()
  }
}
