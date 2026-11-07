package altitude.core.integration

import altitude.test.IntegrationTestUtil
import java.nio.file.Files
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

import altitude.core.models.Asset
import altitude.core.models.ExtractedMetadata

/**
 * A burst synthesized at test time, for the suites of Sequences and Curation: the frames are `images/2.jpg` shifted by a few
 * pixels and brightened a little, so their checksums differ and their hashes are near; the camera's model and clock are seeded as
 * upstream metadata on the asset handed to the pipeline, which the extraction keeps since the file carries none.
 */
trait BurstFrames { self: IntegrationTestCore =>
  import BurstFrames.*

  /**
   * A frame imported with the camera's model, its `Date/Time Original` and its `Sub-Sec Time Original` seeded as upstream
   * metadata, so no EXIF writer is needed
   */
  protected def importFrame(
      bytes: Array[Byte],
      taken: Option[String] = Some(TAKEN),
      subSecond: Option[String] = None,
      model: Option[String] = Some(MODEL),
      fileName: String = "burst.jpg"): Asset = {
    val staged = testApp.service.staging.stage(bytes)
    val dataAsset = testApp.service.library.stagedFileToAsset(fileName, staged)
    val metadata = ExtractedMetadata()
    model.foreach(metadata.addValue("Exif IFD0", "Model", _))
    taken.foreach(metadata.addValue("Exif SubIFD", "Date/Time Original", _))
    subSecond.foreach(metadata.addValue("Exif SubIFD", "Sub-Sec Time Original", _))
    testApp.service.library.addAsset(dataAsset.copy(asset = dataAsset.asset.copy(extractedMetadata = metadata)))
  }

  /** A frame imported with no metadata at all, neither a camera model nor a clock, under `fileName`, so only its name dates it */
  protected def importNamed(bytes: Array[Byte], fileName: String): Asset =
    importFrame(bytes, taken = None, model = None, fileName = fileName)
}

object BurstFrames {
  val MODEL = "Canon EOS R5"
  val TAKEN = "2024:06:01 12:00:00"

  /** The seeded capture time `seconds` after `TAKEN`, in EXIF's spelling; past a minute, the minutes are spelled too */
  def takenAt(seconds: Int): String = f"2024:06:01 12:${seconds / 60}%02d:${seconds % 60}%02d"

  private lazy val base: Array[Byte] = Files.readAllBytes(IntegrationTestUtil.getImportAsset("images/2.jpg").path)

  /**
   * Burst frame `n` of `images/2.jpg`: the file as it is for 0, else a copy shifted `n` pixels each way (the edge filled by its
   * border) and brightened by `6n` levels, re-encoded, so its checksum differs and its hash is near; at `scale` of its size
   */
  def frame(n: Int, scale: Double = 1.0): Array[Byte] =
    if (n == 0 && scale == 1.0) base
    else
      withDecoded(base) {
        image =>
          val shifted = new Mat()
          val shift = Mat.eye(2, 3, CvType.CV_64F)
          shift.put(0, 2, n.toDouble)
          shift.put(1, 2, n.toDouble)
          try {
            Imgproc.warpAffine(image, shifted, shift, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            Core.add(shifted, new Scalar(6.0 * n, 6.0 * n, 6.0 * n), shifted)
            if (scale != 1.0) Imgproc.resize(shifted, shifted, new Size(), scale, scale, Imgproc.INTER_AREA)
            encode(shifted)
          } finally List(shifted, shift).foreach(_.release())
      }

  /** A different picture (`people/meme-ben.jpg`) at the frames' size, so only the hash tells it from a frame */
  lazy val otherPicture: Array[Byte] =
    withDecoded(base) {
      frameImage =>
        withDecoded(Files.readAllBytes(IntegrationTestUtil.getImportAsset("people/meme-ben.jpg").path)) {
          other =>
            val resized = new Mat()
            try {
              Imgproc.resize(other, resized, frameImage.size(), 0, 0, Imgproc.INTER_AREA)
              encode(resized)
            } finally resized.release()
        }
    }

  private def withDecoded[A](bytes: Array[Byte])(f: Mat => A): A = {
    val image = Imgcodecs.imdecode(new MatOfByte(bytes*), Imgcodecs.IMREAD_COLOR)
    try f(image)
    finally image.release()
  }

  private def encode(image: Mat): Array[Byte] = {
    val buffer = new MatOfByte()
    try {
      Imgcodecs.imencode(".jpg", image, buffer)
      buffer.toArray
    } finally buffer.release()
  }
}
