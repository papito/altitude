package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll

class UnitSuiteBundle extends AllUnitTestSuites with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@")
    println("UNIT TESTS")
    println("@@@@@@@@@@\n")
  }
}
