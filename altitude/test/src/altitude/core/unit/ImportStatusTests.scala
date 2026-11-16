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
 * An Import's status as `includes/import_status` and `includes/import_title` render it: the bar's segments, the count chips and
 * the title, for an Import that knows how many files to expect
 */
@DoNotDiscover class ImportStatusTests extends funsuite.AnyFunSuite with TestFocus {

  /** An upload's Import, in progress and still taking files */
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
     * An upload in progress that expects 24 files and has 6: 3 imported, 1 duplicate and 2 pending.
     *
     * Assertions:
     *
     * The bar and the chips show 3 imported, 1 duplicate and the other 20 of the 24 as remaining, and the title counts the 24
     * expected. The status feed's message says the same.
     *
     * Edge cases:
     *
     * With no expected number it goes by the files it has: 2 remaining, titled "6 files".
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
     * An upload in progress that expected 4 files and has 8: 5 imported and 3 pending.
     *
     * Assertions:
     *
     * The bar and the chips show 5 imported and 3 remaining, and it is titled "8 files".
     *
     * Edge cases:
     *
     * With every file settled, nothing remains: no remainder in the bar and no chip for it.
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
     * An upload that expected 24 files and was closed with 6: 5 imported and 1 failed. And the same upload just cancelled, marked
     * complete with 2 files still pending, before it closed.
     *
     * Assertions:
     *
     * The closed Import shows 5 imported and 1 failed with nothing remaining, titled "6 files": the 18 that never came are
     * nowhere.
     *
     * Edge cases:
     *
     * The cancelled one counts only its 2 pending files as remaining and is titled by the 8 it has.
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
