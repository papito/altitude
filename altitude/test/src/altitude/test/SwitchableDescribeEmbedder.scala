package altitude.test

import altitude.core.models.SceneEmbedding
import altitude.core.util.DescribeEmbedder
import altitude.core.util.ModelFiles
import altitude.core.util.OnnxDescribeEmbedder

/**
 * The test instances' Describe embedder: off, it embeds nothing, so no description reaches SigLIP 2's text tower; on, it answers
 * the real model's embedding, from the instance's model files. A suite whose subject is the Describe search turns it on
 * (`IntegrationTestCore.usesDescribeModel`, `withDescribeModel`).
 *
 * The real embedder is built with this one and costs nothing until its first description, which opens the text tower on the
 * thread that asked, a test's: a tower that fails to open fails that test with its own error, and a test JVM that never embeds a
 * description with the model on never loads it (about 570 MB). It keeps its own cache of the descriptions used last, so the
 * suites that repeat a description embed it once.
 */
class SwitchableDescribeEmbedder(models: ModelFiles) extends DescribeEmbedder with ModelSwitch {

  // Read on whatever thread searches, set on the test's
  @volatile private var switchedOn = false

  private val onnx = OnnxDescribeEmbedder(models)

  override def on: Boolean = switchedOn

  override def turnOn(): Unit = switchedOn = true

  override def turnOff(): Unit = switchedOn = false

  override def embed(description: String): Option[SceneEmbedding] = if (switchedOn) onnx.embed(description) else None

  /** Closes the real embedder, and its text tower if a description opened it */
  override def close(): Unit = onnx.close()
}
