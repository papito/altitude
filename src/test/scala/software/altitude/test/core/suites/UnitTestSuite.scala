package software.altitude.test.core.suites

import org.scalatest.BeforeAndAfterAll

class UnitTestSuite extends AllUnitTests with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    println("\n@@@@@@@@@@")
    println("UNIT TESTS")
    println("@@@@@@@@@@\n")
  }
}
