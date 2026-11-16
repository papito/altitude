import org.scalatest.Tag

trait TestFocus {

  /**
   * Tags the tests to run alone: `test("work in progress", Focused) { ... }`, then `make test-focused`, or one bundle's
   * `make test-focused-psql` (`-sqlite`, `-unit`, `-controllers`)
   */
  object Focused extends Tag("focused")
}
