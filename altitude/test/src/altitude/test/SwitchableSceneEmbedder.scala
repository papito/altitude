package altitude.test

import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import org.opencv.core.CvType
import org.opencv.core.Mat

import altitude.core.models.SceneEmbeddings
import altitude.core.util.ModelFiles
import altitude.core.util.OnnxSceneEmbedder
import altitude.core.util.SceneEmbedder

/**
 * The test instances' Scene embedder: off, it embeds nothing, so a fixture import runs no Scene model; on, it answers the real
 * models' embeddings. A suite whose subject is the embeddings turns it on (`IntegrationTestCore.usesSceneModels`,
 * `withSceneModels`). The models open at the first `turnOn`, on the test's thread, so a model that fails to open fails the test
 * with its own error rather than an import thread's WARN, and a JVM that never turns it on never loads them (about 1.2 GB).
 *
 * Answers are cached for the instance's life by the image's pixels: suites import the same synthesized frames (`BurstFrames`)
 * test after test, and a pass is a pure function of the pixels. Two threads embedding the same new image both run the models and
 * store the same answer.
 */
class SwitchableSceneEmbedder(models: ModelFiles) extends SceneEmbedder with ModelSwitch {

  // Read on the import dispatcher's threads, set on the test's
  @volatile private var switchedOn = false

  // Opened by the first turnOn, set before the switch so an embed that sees it on sees the models
  @volatile private var onnx: Option[OnnxSceneEmbedder] = None

  // By `pixelKey`
  private val embedded = new ConcurrentHashMap[String, SceneEmbeddings]()

  override def on: Boolean = switchedOn

  /** Opens the real models on first use, then switches them on; a model that fails to open throws here */
  override def turnOn(): Unit = synchronized {
    if (onnx.isEmpty) onnx = Some(OnnxSceneEmbedder(models))
    switchedOn = true
  }

  /** Switches the real models off, leaving them open until `close` */
  override def turnOff(): Unit = switchedOn = false

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

  override def close(): Unit = onnx.foreach(_.close())

  /**
   * The SHA-256 of an 8-bit image's size, type and pixels; none for another depth, which is embedded every time. Decoded images
   * are 8-bit (`ImageUtil.matFromBytes`).
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
