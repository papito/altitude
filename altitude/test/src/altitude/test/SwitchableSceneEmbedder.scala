package altitude.test

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
 */
class SwitchableSceneEmbedder extends SceneEmbedder {

  // Read on the import dispatcher's threads, set on the test's
  @volatile private var switchedOn = false

  // Opened by the first turnOn, set before the switch so an embed that sees it on sees the models
  @volatile private var onnx: Option[OnnxSceneEmbedder] = None

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
    case Some(models) if switchedOn => models.embed(image, name)
    case _ => SceneEmbeddings()
  }

  /** Closes the real models if they were opened */
  override def close(): Unit = onnx.foreach(_.close())
}
