package altitude.core.unit

import altitude.test.TestFocus
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.Import
import altitude.core.models.ImportSource

/**
 * An Import's status as the Import history and the status feed render it (includes/import_status, includes/import_title): the
 * bar's segments, the count chips and the title, for an Import that knows how many files to expect
 */
@DoNotDiscover class ImportStatusTests extends funsuite.AnyFunSuite with TestFocus {

  /** An upload's Import of files, with no name, in progress and still taking files */
  private def upload(expected: Option[Int], imported: Int = 0, duplicate: Int = 0, failed: Int = 0, pending: Int = 0): Import =
    Import(
      id = Some("import"),
      source = ImportSource.Upload,
      expectedFiles = expected,
      imported = imported,
      duplicate = duplicate,
      failed = failed,
      pending = pending)

  private def status(anImport: Import): String = includes.html.import_status(anImport).body

  /** The bar's segments, in order: each outcome's class with the number of files it stands for */
  private def segments(anImport: Import): List[(String, Int)] =
    """<span class="(\w+)" style="--files: (\d+)"></span>""".r
      .findAllMatchIn(status(anImport))
      .map(segment => segment.group(1) -> segment.group(2).toInt)
      .toList

  /** The count chips, in order: each one's class with what it says */
  private def chips(anImport: Import): List[(String, String)] =
    """<li class="(\w+)">([^<]*)</li>""".r
      .findAllMatchIn(status(anImport))
      .map(chip => chip.group(1) -> chip.group(2).trim)
      .toList

  /** What the Import is titled */
  private def title(anImport: Import): String =
    """>([^<]*)</strong>""".r
      .findFirstMatchIn(includes.html.import_title(anImport).body)
      .get
      .group(1)
      .trim
      .replaceAll("\\s+", " ")

  test("An Import in progress that expects more files than it has shows the files to come as remaining") {

    /**
     * Setup:
     *
     * An upload of files in progress that expects 24 files and has been given 6 of them: 3 imported, 1 already in the Library and
     * 2 pending.
     *
     * Assertions:
     *
     * Its bar has a segment of 3 imported, one of 1 duplicate, and the rest of the 24, 20 files, as the unfilled remainder. Its
     * chips say 3 imported, 1 already in the Library and 20 remaining. It is titled "24 files", the number it expects. The
     * message the status feed pushes says the same: 20 remaining, under the title "24 files".
     *
     * Edge cases:
     *
     * The same Import with no expected number goes by the files it has: 2 remaining, a remainder of 2, titled "6 files".
     */
    val expecting = upload(expected = Some(24), imported = 3, duplicate = 1, pending = 2)

    segments(expecting) shouldBe List("imported" -> 3, "duplicate" -> 1, "pending" -> 20)
    chips(expecting) shouldBe List(
      "imported" -> "3 imported",
      "duplicate" -> "1 already in the Library",
      "pending" -> "20 remaining")
    title(expecting) shouldBe "24 files"
    status(expecting) should include("""class="status in-progress"""")

    val pushed = ujson.read(ImportStatusWsActor.statusMessage(expecting))(Api.Field.ImportStatus.CONTENT).str
    pushed should include("""<li class="pending">20 remaining</li>""")
    (pushed should include).regex("""hx-swap-oob="true">24\s+files</strong>""")

    val unknowing = expecting.copy(expectedFiles = None)
    segments(unknowing) shouldBe List("imported" -> 3, "duplicate" -> 1, "pending" -> 2)
    chips(unknowing).last shouldBe ("pending" -> "2 remaining")
    title(unknowing) shouldBe "6 files"
  }

  test("An Import in progress that has more files than it expected goes by the files it has") {

    /**
     * Setup:
     *
     * An upload of files in progress that expected 4 files and has been given 8: 5 imported and 3 pending.
     *
     * Assertions:
     *
     * Its bar has a segment of 5 imported and a remainder of 3, its chips say 5 imported and 3 remaining, and it is titled "8
     * files".
     *
     * Edge cases:
     *
     * With every file it has settled and nothing more expected, nothing remains: no remainder in the bar and no chip for it.
     */
    val more = upload(expected = Some(4), imported = 5, pending = 3)

    segments(more) shouldBe List("imported" -> 5, "pending" -> 3)
    chips(more) shouldBe List("imported" -> "5 imported", "pending" -> "3 remaining")
    title(more) shouldBe "8 files"

    val settled = upload(expected = Some(4), imported = 8)
    segments(settled) shouldBe List("imported" -> 8)
    chips(settled) shouldBe List("imported" -> "8 imported")
  }

  test("An Import that takes no more files shows what was recorded, whatever it expected") {

    /**
     * Setup:
     *
     * An upload of files that expected 24 files and was closed with 6: 5 imported and 1 failed. And the same upload as it was
     * cancelled, marked complete with 2 of its files still pending, before it closed.
     *
     * Assertions:
     *
     * The closed Import's bar has a segment of 5 imported and one of 1 failed and no remainder, its chips say 5 imported and 1
     * failed, nothing remaining, and it is titled "6 files": the 18 files that never came are nowhere.
     *
     * Edge cases:
     *
     * The cancelled one, still in progress while its last files settle, counts those 2 alone as remaining and is titled by the 8
     * files it has: no file is to come any more.
     */
    val closed =
      upload(expected = Some(24), imported = 5, failed = 1).copy(isComplete = true, closedAt = Some(LocalDateTime.now()))

    segments(closed) shouldBe List("imported" -> 5, "failed" -> 1)
    chips(closed) shouldBe List("imported" -> "5 imported", "failed" -> "1 failed")
    title(closed) shouldBe "6 files"
    (status(closed) should not).include("in-progress")

    val cancelled = upload(expected = Some(24), imported = 5, failed = 1, pending = 2).copy(isComplete = true)
    segments(cancelled) shouldBe List("imported" -> 5, "failed" -> 1, "pending" -> 2)
    chips(cancelled).last shouldBe ("pending" -> "2 remaining")
    title(cancelled) shouldBe "8 files"
  }
}
