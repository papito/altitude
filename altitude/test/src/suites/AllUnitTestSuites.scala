package suites

import altitude.core.unit.UserModelTests
import org.scalatest.Suites


abstract class AllUnitTestSuites extends Suites (
  new UserModelTests
)