package altitude.test

import org.opencv.core.Mat

import altitude.core.models.SceneEmbeddings
import altitude.core.util.OnnxSceneEmbedder
import altitude.core.util.SceneEmbedder

/**
 * The test instances' Scene embedder: off, it embeds nothing, so a fixture import runs no Scene model; on, it answers the real
 * models' embeddings. A suite whose subject is the embeddings turns it on (`IntegrationTestCore.usesSceneModels`,
 * `withSceneModels`). The real models are opened the first time an image is embedded with it on, so a test JVM that never turns
 * it on never loads them (about 1.1 GB).
 */
class SwitchableSceneEmbedder extends SceneEmbedder {

  /** Whether `embed` runs the real models; read on the import dispatcher's threads, set on the test's */
  @volatile var on: Boolean = false

  @volatile private var opened = false

  private lazy val onnx: OnnxSceneEmbedder = {
    val embedder = OnnxSceneEmbedder()
    opened = true
    embedder
  }

  override def embed(image: Mat, name: String): SceneEmbeddings =
    if (on) onnx.embed(image, name) else SceneEmbeddings()

  /** Closes the real models if they were opened */
  override def close(): Unit = if (opened) onnx.close()
}
