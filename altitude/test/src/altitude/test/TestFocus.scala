package altitude.test

import org.scalatest.Tag

trait TestFocus {

  /**
   * Tags the tests to run alone: `test("work in progress", Focused) { ... }`, then `make test-focused` for every bundle or
   * `make test-focused-sqlite` (or `-psql`, `-unit`, `-controllers`) for one. A name filter (`-z`) does not reach the suites a
   * bundle nests.
   */
  object Focused extends Tag("focused")
}
