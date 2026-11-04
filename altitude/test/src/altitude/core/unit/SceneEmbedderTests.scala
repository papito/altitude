package altitude.core.unit

import altitude.test.TestFocus
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.scalatest.BeforeAndAfterAll
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.compiletime.uninitialized

import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.util.SceneEmbedder

/**
 * The Scene embeddings of [[SceneEmbedder]] over the `people/` photos, as orderings of similarities, never values: the one place
 * the preprocessing is checked against what the models were trained on. Needs the model files, as the face tests need ArcFace.
 */
@DoNotDiscover class SceneEmbedderTests extends AnyFunSuite with BeforeAndAfterAll with TestFocus {

  Loader.load(classOf[opencv_java])

  /** One embedder for the suite, opened before the first test and closed after the last: a session per Scene model is 350 MB */
  private var embedder: SceneEmbedder = uninitialized

  override def beforeAll(): Unit = embedder = new SceneEmbedder()

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

  test("Each Scene model's embedding of a photo has its dimensions and unit norm, and is the same on a second pass") {

    /**
     * Setup:
     *
     * `people/bullock.jpg`, embedded twice.
     *
     * Assertions:
     *
     * For each Scene model, the embedding has the model's dimensions and a sum of squares of 1 within 1e-4, so two embeddings
     * compare by their dot product; the second pass gives the same vector, a similarity of 1 within float noise.
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

  test("A shifted, brightened copy of a photo is closer to it than a flipped copy, a heavy crop, or another photo") {

    /**
     * Setup:
     *
     * `people/bullock.jpg`; a copy shifted by three pixels each way and brightened by 12 levels, as the next frame of a burst
     * differs; a copy flipped upside down (not mirrored: CLIP reads a mirrored picture as the same scene and ranks it beside the
     * burst-like copy); the middle third of the photo; and `people/affleck.jpg`, a different picture.
     *
     * Assertions:
     *
     * For each Scene model, the burst-like copy is more similar to the original than the flipped copy, the crop and the other
     * photo are, so a burst frame stands out from a reframed or a different shot; the other photo is the least similar of all.
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
          toOther should be < toCrop
      }
    finally List(image, shifted, flipped, crop, other).foreach(_.release())
  }

  test("A gray and a BGRA decode of a picture embed as the BGR one does") {

    /**
     * Setup:
     *
     * `people/bullock.jpg` as decoded (BGR), the same converted to BGRA (as a PNG with alpha decodes), and the same converted to
     * one gray channel (as a grayscale file decodes) beside that gray picture converted back to three equal channels.
     *
     * Assertions:
     *
     * For each Scene model, the BGRA image embeds as the BGR one and the one-channel image as its three-channel copy: each pair
     * is one picture to the model, so the similarity is 1 within float noise, an equality rather than an ordering.
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
     * `embed` returns no embedding for either Scene model and does not throw, as a measurement failure is the file's, never the
     * import's.
     */
    val image = new Mat(120, 160, CvType.CV_8UC2, new Scalar(128, 128))
    try embeddings(image) shouldBe SceneEmbeddings()
    finally image.release()
  }
}
