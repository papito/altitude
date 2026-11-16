package altitude.core.unit

import altitude.test.TestFocus
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.util.ImageUtil

/** The EXIF orientation applied in OpenCV by [[ImageUtil.orient]] */
@DoNotDiscover class ImageUtilTests extends AnyFunSuite with TestFocus {

  Loader.load(classOf[opencv_java])

  /** The image's pixels by row, a one-channel image */
  private def pixels(image: Mat): Seq[Seq[Int]] =
    (0 until image.rows).map(row => (0 until image.cols).map(col => image.get(row, col)(0).toInt))

  test("Each EXIF orientation turns the image upright") {

    /**
     * Setup:
     *
     * A two-row, three-column image of grey levels 1 to 6, taken as the stored pixels under each EXIF orientation 1 to 8 and
     * under a value the tag cannot hold.
     *
     * Assertions:
     *
     * Each orientation puts the pixels where the upright image has them, 5 to 8 giving three rows of two. A value outside the
     * tag's range leaves the image as it is. Each result is a new image, and the stored one is unchanged.
     */
    val stored = new Mat(2, 3, CvType.CV_8UC1)
    stored.put(0, 0, Array[Byte](1, 2, 3, 4, 5, 6))

    def upright(orientation: Int): Seq[Seq[Int]] =
      val image = ImageUtil.orient(stored, orientation)
      try pixels(image)
      finally image.release()

    upright(1) shouldBe Seq(Seq(1, 2, 3), Seq(4, 5, 6))
    upright(2) shouldBe Seq(Seq(3, 2, 1), Seq(6, 5, 4))
    upright(3) shouldBe Seq(Seq(6, 5, 4), Seq(3, 2, 1))
    upright(4) shouldBe Seq(Seq(4, 5, 6), Seq(1, 2, 3))
    upright(5) shouldBe Seq(Seq(1, 4), Seq(2, 5), Seq(3, 6))
    upright(6) shouldBe Seq(Seq(4, 1), Seq(5, 2), Seq(6, 3))
    upright(7) shouldBe Seq(Seq(6, 3), Seq(5, 2), Seq(4, 1))
    upright(8) shouldBe Seq(Seq(3, 6), Seq(2, 5), Seq(1, 4))
    upright(9) shouldBe Seq(Seq(1, 2, 3), Seq(4, 5, 6))
    pixels(stored) shouldBe Seq(Seq(1, 2, 3), Seq(4, 5, 6))
    stored.release()
  }
}
