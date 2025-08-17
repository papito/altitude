package suites

import altitude.core.controller.HealthCheckTests
import org.scalatest.Suites


abstract class AllControllerTestSuites extends Suites (
  new HealthCheckTests
)
