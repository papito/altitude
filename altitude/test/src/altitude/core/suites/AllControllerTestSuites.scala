package altitude.core.suites

import org.scalatest.Suites

import altitude.core.controller.AssetControllerTests
import altitude.core.controller.ContentViewControllerTests
import altitude.core.controller.HealthControllerTests
import altitude.core.controller.IndexControllerTests
import altitude.core.controller.LoginControllerTests

abstract class AllControllerTestSuites
  extends Suites(
    new HealthControllerTests(),
    new IndexControllerTests(),
    new AssetControllerTests(),
    new LoginControllerTests(),
    new ContentViewControllerTests()
  )
