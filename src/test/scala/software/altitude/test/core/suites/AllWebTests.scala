package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.test.core.web.IndexControllerTests
import software.altitude.test.core.web.SetupControllerTests

abstract class AllWebTests(val config: Map[String, Any]) extends Suites (
  new SetupControllerTests(config),
  new IndexControllerTests(config)
)
