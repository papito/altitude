package altitude.test

import altitude.core.models.SceneEmbedding
import altitude.core.util.DescribeEmbedder
import altitude.core.util.ModelFiles
import altitude.core.util.OnnxDescribeEmbedder

/**
 * The test instances' Describe embedder: off, it embeds nothing; on, it answers the real model's embedding. A suite whose subject
 * is the Describe search turns it on (`IntegrationTestCore.usesDescribeModel`, `withDescribeModel`).
 *
 * The real embedder costs nothing until its first description, which opens the text tower on the test's thread: a tower that
 * fails to open fails that test with its own error, and a JVM that never embeds with the model on never loads it (about 570 MB).
 * Its cache embeds a description repeated across suites once.
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
