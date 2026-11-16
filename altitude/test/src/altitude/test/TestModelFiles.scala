package altitude.test

import altitude.core.Altitude
import altitude.core.util.ModelFiles

/**
 * The model files for unit suites that run a model without a test instance: the test config's `models.dir`, by default the
 * per-user cache the test instances and the dev server share. Ensured once a JVM, so a machine that has them never downloads them
 * again.
 */
object TestModelFiles {
  lazy val models: ModelFiles = ModelFiles.ensure(Altitude.testConfig())
}
