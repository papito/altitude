package altitude.core.unit

import altitude.test.TestFocus
import altitude.test.TestModelFiles
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.compiletime.uninitialized
import scala.util.Try

import altitude.core.SceneEmbedderClosedException
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.util.OnnxSceneEmbedder

/**
 * [[OnnxSceneEmbedder]]'s Scene embeddings of the `people/` photos, as orderings of similarities, never values: the one place the
 * preprocessing is checked against what the models were trained on. Needs the model files (`TestModelFiles`).
 */
@DoNotDiscover class SceneEmbedderTests extends AnyFunSuite with BeforeAndAfterAll with TestFocus {

  Loader.load(classOf[opencv_java])

  /** One embedder for the suite, since a session per Scene model is about 550 MB */
  private var embedder: OnnxSceneEmbedder = uninitialized

  override def beforeAll(): Unit = embedder = OnnxSceneEmbedder(TestModelFiles.models)

  override def afterAll(): Unit = embedder.close()

  private def fixture(relPath: String): Mat =
    val image = Imgcodecs.imread(getClass.getResource(s"/import/$relPath").getPath, Imgcodecs.IMREAD_COLOR)
    if image.empty() then fail(s"Could not read $relPath")
    image

  private def embeddings(image: Mat): SceneEmbeddings = embedder.embed(image, "fixture")

  private def embedding(image: Mat, model: SceneModel): SceneEmbedding = embeddings(image).of(model).value

  /** The image shifted three pixels each way (the edge filled by its border) and brightened by 12 levels: a burst's next frame */
  private def shiftedAndBrightened(image: Mat): Mat =
    val shifted = new Mat()
    val shift = Mat.eye(2, 3, CvType.CV_64F)
    shift.put(0, 2, 3.0)
    shift.put(1, 2, 3.0)
    try
      Imgproc.warpAffine(image, shifted, shift, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
      Core.add(shifted, new Scalar(12, 12, 12), shifted)
      shifted
    finally shift.release()

  private def upsideDown(image: Mat): Mat =
    val out = new Mat()
    Core.flip(image, out, 0)
    out

  /** The middle third of the image each way: a heavy crop that keeps little of the scene */
  private def cropped(image: Mat): Mat =
    image.submat(new Rect(image.cols() / 3, image.rows() / 3, image.cols() / 3, image.rows() / 3))

  private def converted(image: Mat, code: Int): Mat =
    val out = new Mat()
    Imgproc.cvtColor(image, out, code)
    out

  /**
   * A deterministic BGR image: diagonal ramps of a different period per channel, between a flat blue band down its left tenth and
   * a flat red one down its right tenth
   */
  private def pattern(width: Int, height: Int): Mat =
    val bytes = new Array[Byte](width * height * 3)
    val band = width / 10
    for
      y <- 0 until height
      x <- 0 until width
    do
      val i = (y * width + x) * 3
      if x < band then bytes(i) = 255.toByte
      else if x >= width - band then bytes(i + 2) = 255.toByte
      else
        bytes(i) = ((x * 3 + y * 5) % 256).toByte
        bytes(i + 1) = ((x * 7 + y * 2) % 256).toByte
        bytes(i + 2) = ((x + y * 11) % 256).toByte
    val image = new Mat(height, width, CvType.CV_8UC3)
    image.put(0, 0, bytes)
    image

  /** A BGR image's pixels as packed RGB bytes, row-major: the form a Scene model's prepared input has */
  private def rgbBytes(bgr: Mat): Array[Byte] =
    val rgb = converted(bgr, Imgproc.COLOR_BGR2RGB)
    try
      val bytes = new Array[Byte](Math.toIntExact(rgb.total() * rgb.channels()))
      rgb.get(0, 0, bytes)
      bytes
    finally rgb.release()

  private def resizedRgb(image: Mat, width: Int, height: Int, interpolation: Int): Array[Byte] =
    val resized = new Mat()
    try
      Imgproc.resize(image, resized, new Size(width, height), 0, 0, interpolation)
      rgbBytes(resized)
    finally resized.release()

  /** The center square of the image after the resize, as packed RGB bytes */
  private def centerSquareRgb(image: Mat, width: Int, height: Int, side: Int, interpolation: Int): Array[Byte] =
    val resized = new Mat()
    try
      Imgproc.resize(image, resized, new Size(width, height), 0, 0, interpolation)
      val square = resized.submat(new Rect((width - side) / 2, (height - side) / 2, side, side))
      try rgbBytes(square)
      finally square.release()
    finally resized.release()

  /** The RGB levels of a prepared input's pixel */
  private def pixel(prepared: Array[Byte], side: Int, x: Int, y: Int): Seq[Int] =
    (0 until 3).map(channel => prepared((y * side + x) * 3 + channel) & 0xff)

  private def sha256(bytes: Array[Byte]): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  test("Each Scene model's embedding of a photo has its dimensions and unit norm, and is the same on a second pass") {

    /**
     * Setup:
     *
     * `people/bullock.jpg`, embedded twice.
     *
     * Assertions:
     *
     * For each Scene model, the embedding has the model's dimensions and unit norm within 1e-4, so embeddings compare by dot
     * product, and the second pass gives the same vector within float noise.
     */
    val image = fixture("people/bullock.jpg")
    try
      SceneModel.values.foreach {
        model =>
          val first = embedding(image, model)
          first.values.length shouldBe model.dimensions
          first.values.map(v => v.toDouble * v).sum shouldBe 1.0 +- 1e-4
          first.similarity(embedding(image, model)) shouldBe 1.0 +- 1e-5
      }
    finally image.release()
  }

  test("DINOv2's input is the center square of the image with its short side fitted, the ends of the long side left out") {

    /**
     * Setup:
     *
     * Three synthetic images with a blue band down the left edge and a red one down the right: a 1200x800 landscape and a 600x900
     * portrait, both shrunk, and a 150x100 landscape, enlarged.
     *
     * Assertions:
     *
     * Each prepared input is 224x224 RGB, byte for byte the image resized so its short side is 224 (`INTER_AREA` shrinking,
     * `INTER_CUBIC` enlarging) and cut to its center square. Each one's SHA-256 is pinned, since DINOv2's threshold is calibrated
     * to this input. The landscape's bands fall outside the square.
     */
    val side = SceneModel.Dinov2.inputSize
    side shouldBe 224
    val landscape = pattern(1200, 800)
    val portrait = pattern(600, 900)
    val small = pattern(150, 100)
    try
      val ofLandscape = OnnxSceneEmbedder.prepare(landscape, SceneModel.Dinov2)
      (ofLandscape should have).length(side * side * 3)
      ofLandscape shouldBe centerSquareRgb(landscape, 336, 224, side, Imgproc.INTER_AREA)
      sha256(ofLandscape) shouldBe "bc94e9a3b74b04e8c3bd9dc0d22fdf2608f398ab1a1735a25ba35b4087296d74"
      List(0, side - 1).foreach {
        x =>
          pixel(ofLandscape, side, x, side / 2) should not be Seq(0, 0, 255)
          pixel(ofLandscape, side, x, side / 2) should not be Seq(255, 0, 0)
      }

      val ofPortrait = OnnxSceneEmbedder.prepare(portrait, SceneModel.Dinov2)
      ofPortrait shouldBe centerSquareRgb(portrait, 224, 336, side, Imgproc.INTER_AREA)
      sha256(ofPortrait) shouldBe "728f0e39a808380a1d3b3bf3a2a8859b4fcc6bb30123c598f7ea037391e1cd73"

      val ofSmall = OnnxSceneEmbedder.prepare(small, SceneModel.Dinov2)
      ofSmall shouldBe centerSquareRgb(small, 336, 224, side, Imgproc.INTER_CUBIC)
      sha256(ofSmall) shouldBe "31398e2a0b00a8ffc07789e8090c0bc135adfd2d1174102aa9c936c6b694f0aa"
    finally List(landscape, portrait, small).foreach(_.release())
  }

  test("SigLIP 2's input is the whole image squashed to its square, the edges of the frame kept") {

    /**
     * Setup:
     *
     * The banded synthetic images: a 1200x800 landscape and a 600x900 portrait, both larger than the input, a 150x100 landscape
     * smaller than it, and a 1000x200 strip, wider and less tall.
     *
     * Assertions:
     *
     * Each prepared input is 256x256 RGB, byte for byte the whole image resized to 256x256 regardless of aspect, so the frame's
     * edges stay in view: the landscape's first column is the blue band and its last the red one.
     *
     * Edge cases:
     *
     * The resize is `INTER_AREA` only where neither side is enlarged; the small image and the strip, enlarged on one side, use
     * `INTER_CUBIC`.
     */
    val side = SceneModel.Siglip2.inputSize
    side shouldBe 256
    val landscape = pattern(1200, 800)
    val portrait = pattern(600, 900)
    val small = pattern(150, 100)
    val strip = pattern(1000, 200)
    try
      val ofLandscape = OnnxSceneEmbedder.prepare(landscape, SceneModel.Siglip2)
      (ofLandscape should have).length(side * side * 3)
      ofLandscape shouldBe resizedRgb(landscape, side, side, Imgproc.INTER_AREA)
      pixel(ofLandscape, side, 0, side / 2) shouldBe Seq(0, 0, 255)
      pixel(ofLandscape, side, side - 1, side / 2) shouldBe Seq(255, 0, 0)

      OnnxSceneEmbedder.prepare(portrait, SceneModel.Siglip2) shouldBe resizedRgb(portrait, side, side, Imgproc.INTER_AREA)
      OnnxSceneEmbedder.prepare(small, SceneModel.Siglip2) shouldBe resizedRgb(small, side, side, Imgproc.INTER_CUBIC)
      OnnxSceneEmbedder.prepare(strip, SceneModel.Siglip2) shouldBe resizedRgb(strip, side, side, Imgproc.INTER_CUBIC)
    finally List(landscape, portrait, small, strip).foreach(_.release())
  }

  test("A shifted, brightened copy of a photo is closer to it than a flipped copy, a heavy crop, or another photo") {

    /**
     * Setup:
     *
     * `people/bullock.jpg`; a burst-like copy shifted three pixels each way and brightened by 12 levels; a copy flipped upside
     * down (not mirrored, which SigLIP 2 ranks beside the burst-like copy); the middle third of the photo; and
     * `people/affleck.jpg`.
     *
     * Assertions:
     *
     * For each Scene model, the burst-like copy is closer to the original than the flipped copy, the crop and the other photo,
     * and the other photo is farther than the flipped copy.
     *
     * Edge cases:
     *
     * By DINOv2 the other photo is also the farthest of all. SigLIP 2 reads what a picture shows and finds another portrait about
     * as near as a tight crop (0.69 and 0.68).
     */
    val image = fixture("people/bullock.jpg")
    val shifted = shiftedAndBrightened(image)
    val flipped = upsideDown(image)
    val crop = cropped(image)
    val other = fixture("people/affleck.jpg")
    try
      SceneModel.values.foreach {
        model =>
          val original = embedding(image, model)
          val toShifted = original.similarity(embedding(shifted, model))
          val toFlipped = original.similarity(embedding(flipped, model))
          val toCrop = original.similarity(embedding(crop, model))
          val toOther = original.similarity(embedding(other, model))
          toShifted should be > toFlipped
          toShifted should be > toCrop
          toShifted should be > toOther
          toOther should be < toFlipped
          if model == SceneModel.Dinov2 then toOther should be < toCrop
      }
    finally List(image, shifted, flipped, crop, other).foreach(_.release())
  }

  test("A gray and a BGRA decode of a picture embed as the BGR one does") {

    /**
     * Setup:
     *
     * `people/bullock.jpg` as decoded (BGR), converted to BGRA (as a PNG with alpha decodes), and converted to one gray channel
     * (as a grayscale file decodes) beside that gray converted back to three equal channels.
     *
     * Assertions:
     *
     * For each Scene model, the BGRA image embeds as the BGR one and the one-channel image as its three-channel copy, within
     * float noise.
     */
    val bgr = fixture("people/bullock.jpg")
    val bgra = converted(bgr, Imgproc.COLOR_BGR2BGRA)
    val gray = converted(bgr, Imgproc.COLOR_BGR2GRAY)
    val grayAsBgr = converted(gray, Imgproc.COLOR_GRAY2BGR)
    try
      SceneModel.values.foreach {
        model =>
          embedding(bgr, model).similarity(embedding(bgra, model)) shouldBe 1.0 +- 1e-5
          embedding(gray, model).similarity(embedding(grayAsBgr, model)) shouldBe 1.0 +- 1e-5
      }
    finally List(bgr, bgra, gray, grayAsBgr).foreach(_.release())
  }

  test("An image of an unsupported channel count gives no embedding rather than an error") {

    /**
     * Setup:
     *
     * A flat two-channel image, which no decode produces.
     *
     * Assertions:
     *
     * `embed` returns no embedding for either Scene model rather than throwing, so the failure stays the file's, never the
     * import's.
     */
    val image = new Mat(120, 160, CvType.CV_8UC2, new Scalar(128, 128))
    try embeddings(image) shouldBe SceneEmbeddings()
    finally image.release()
  }

  test("Closing waits for the images being embedded, and a closed embedder refuses") {

    /**
     * Setup:
     *
     * An embedder of the test's own with both sessions open, and four threads each embedding an image of its own in a loop, as
     * the import's threads do. Once each thread has embedded its image, the embedder is closed under them.
     *
     * Assertions:
     *
     * Every thread stops on the closed embedder's `SceneEmbedderClosedException` and on nothing else, and every answer before it
     * carried both models' embeddings: an image under way at the close is embedded whole, and the next finds the embedder closed.
     * No session is closed under a pass, which would then run on freed memory. Afterwards the embedder refuses the same way,
     * where a pass that merely failed would answer no embedding.
     *
     * Edge cases:
     *
     * A second close does nothing.
     */
    val threads = 4
    val pool = Executors.newFixedThreadPool(threads)
    val closing = OnnxSceneEmbedder(TestModelFiles.models)
    val images = (1 to threads).map(thread => pattern(640 + 32 * thread, 480))

    try
      val started = CountDownLatch(threads)
      val embedding = images.map {
        image =>
          pool.submit {
            () =>
              Try(Iterator.continually(closing.embed(image, "fixture")).foreach {
                embeddings =>
                  // A pass on a session closed under it, or just before it, fails: the embedder answers no embedding
                  if SceneModel.values.exists(embeddings.of(_).isEmpty) then
                    throw IllegalStateException("An image came back without an embedding")
                  started.countDown()
              })
          }
      }
      started.await(2, TimeUnit.MINUTES) shouldBe true

      closing.close()

      embedding.foreach(ended => ended.get(2, TimeUnit.MINUTES).failed.get shouldBe a[SceneEmbedderClosedException])
      a[SceneEmbedderClosedException] should be thrownBy closing.embed(images.head, "fixture")

      closing.close()
    finally
      pool.shutdownNow()
      closing.close()
      images.foreach(_.release())
  }
}
