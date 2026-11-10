package altitude.test

import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import org.opencv.core.CvType
import org.opencv.core.Mat

import altitude.core.models.SceneEmbeddings
import altitude.core.util.OnnxSceneEmbedder
import altitude.core.util.SceneEmbedder

/**
 * The test instances' Scene embedder: off, it embeds nothing, so a fixture import runs no Scene model; on, it answers the real
 * models' embeddings. A suite whose subject is the embeddings turns it on (`IntegrationTestCore.usesSceneModels`,
 * `withSceneModels`). The real models are opened when it is first turned on, on the test's thread, so a model that fails to open
 * fails the suite or the test with its own error rather than an import thread's WARN, and a test JVM that never turns it on never
 * loads them (about 1.1 GB).
 *
 * The real models' answers are kept for the instance's life, by the image's pixels: the suites that turn the models on import the
 * same synthesized frames (`BurstFrames`) test after test, and a model pass is a pure function of the pixels, so each image is
 * embedded once. Two threads embedding the same new image at once both run the models and store the same answer.
 */
class SwitchableSceneEmbedder extends SceneEmbedder {

  // Read on the import dispatcher's threads, set on the test's
  @volatile private var switchedOn = false

  // Opened by the first turnOn, set before the switch so an embed that sees it on sees the models
  @volatile private var onnx: Option[OnnxSceneEmbedder] = None

  // The real models' embeddings by the digest of the image's size, type and pixels (`pixelKey`)
  private val embedded = new ConcurrentHashMap[String, SceneEmbeddings]()

  /** Whether `embed` runs the real models */
  def on: Boolean = switchedOn

  /** Opens the real models if they are not open yet, then switches them on; a model that fails to open throws here */
  def turnOn(): Unit = synchronized {
    if (onnx.isEmpty) onnx = Some(OnnxSceneEmbedder())
    switchedOn = true
  }

  /** Switches the real models off, leaving them open until `close` */
  def turnOff(): Unit = switchedOn = false

  override def embed(image: Mat, name: String): SceneEmbeddings = onnx match {
    case Some(models) if switchedOn =>
      pixelKey(image).fold(models.embed(image, name)) {
        key =>
          Option(embedded.get(key)).getOrElse {
            val embeddings = models.embed(image, name)
            embedded.putIfAbsent(key, embeddings)
            embeddings
          }
      }
    case _ => SceneEmbeddings()
  }

  /** Closes the real models if they were opened */
  override def close(): Unit = onnx.foreach(_.close())

  /**
   * The SHA-256 of an 8-bit image's size, type and pixels; none for another depth, which is embedded every time. A decoded image
   * is 8-bit (`ImageUtil.matFromBytes`), so none is the exception.
   */
  private def pixelKey(image: Mat): Option[String] =
    Option.when(image.depth() == CvType.CV_8U) {
      val pixels = new Array[Byte](Math.toIntExact(image.total() * image.elemSize()))
      image.get(0, 0, pixels)
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(s"${image.rows()}x${image.cols()}:${image.`type`()}".getBytes)
      HexFormat.of().formatHex(digest.digest(pixels))
    }
}
