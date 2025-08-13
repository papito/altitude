package software.altitude.test.core

import org.scalatest.Tag

trait TestFocus {
  /**
    * Scalatest tag to run a specific test[s]
    *
    * test("work in progress", Focused) {
    *
    * }
    *
    * To run:
    *
    * sbt> test-only -- -n focused
    *
    * For specific DB suite:
    *
    * sbt> test-only software.altitude.test.core.suites.SqliteSuite -- -n focused
    */
  object Focused extends Tag("focused")
}
