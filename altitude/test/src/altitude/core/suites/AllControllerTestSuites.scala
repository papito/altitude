package altitude.core.suites

import altitude.core.controller.AssetControllerTests
import altitude.core.controller.HealthControllerTests
import altitude.core.controller.IndexControllerTests
import altitude.core.controller.LoginControllerTests
import altitude.core.controller.ContentViewControllerTests
import org.scalatest.Suites

abstract class AllControllerTestSuites extends Suites (
  new HealthControllerTests(),
  new IndexControllerTests(),
  new AssetControllerTests(),
  new LoginControllerTests(),
  new ContentViewControllerTests(),
)
