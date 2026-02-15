package altitude.core.suites

import altitude.core.controller.HealthControllerTests
import altitude.core.controller.IndexControllerTests
import org.scalatest.Suites

abstract class AllControllerTestSuites extends Suites (
  new HealthControllerTests(),
  new IndexControllerTests(),
)
