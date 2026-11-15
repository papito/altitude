package altitude.test

import altitude.core.Altitude
import altitude.core.util.ModelFiles

/**
 * The model files for the tests that run a model without a test instance (the Scene and Describe embedders' and the tokenizer's
 * unit suites): the ones the test config's `models.dir` names, the per-user cache by default, which the test instances and the
 * dev server share. Ensured once a JVM, by the first suite that asks, so a machine that has them never downloads them again.
 */
object TestModelFiles {
  lazy val models: ModelFiles = ModelFiles.ensure(Altitude.testConfig())
}
