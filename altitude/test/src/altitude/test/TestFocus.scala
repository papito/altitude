package altitude.test

import org.scalatest.Tag

trait TestFocus {

  /**
   * Scalatest tag to run a specific test[s]
   *
   * test("work in progress", Focused) {
   *
   * }
   *
   * To run in every suite bundle (Postgres included):
   *
   * make test-focused
   *
   * For a specific bundle:
   *
   * make test-focused-sqlite (or -psql, -unit, -controllers)
   *
   * A test name filter (-z) does not reach the suites a bundle nests, so the tag is the way to run a single test.
   */
  object Focused extends Tag("focused")
}
