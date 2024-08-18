package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.core.Altitude
import software.altitude.test.core.controller.ContentViewControllerTests
import software.altitude.test.core.controller.FileUploadControllerTests
import software.altitude.test.core.controller.IndexControllerTests
import software.altitude.test.core.controller.SetupControllerTests

abstract class AllControllerTestSuites(val testApp: Altitude) extends Suites (
  new SetupControllerTests(testApp),
  new IndexControllerTests(testApp),
  new FileUploadControllerTests(testApp),
  new ContentViewControllerTests(testApp)
)
