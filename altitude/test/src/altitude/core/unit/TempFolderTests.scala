package altitude.core.unit

import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*
import scala.util.Using

import altitude.core.TempFolder

/** The temp folder a start empties when it is the server's own (`TempFolder`), over scratch folders */
@DoNotDiscover class TempFolderTests extends AnyFunSuite with TestFocus {

  private def withScratch[T](f: Path => T): T = {
    val scratch = Files.createTempDirectory("temp-folder-test")
    try f(scratch)
    finally FileUtils.deleteDirectory(scratch.toFile)
  }

  private def entriesOf(dir: Path): List[String] =
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toList.sorted)

  test("A temp folder that is the server's own is made and emptied at the start") {

    /**
     * Setup:
     *
     * A scratch folder standing for a container's `/tmp`, and in it a temp folder holding what a killed run leaves: a spooled
     * file, a folder with a file in it, and a symbolic link to a folder outside, which holds a file. Then a temp folder that does
     * not exist yet.
     *
     * Assertions:
     *
     * Prepared as the server's own, the first folder is still there and empty, and three entries are counted as removed; what
     * lies beside it in `/tmp`, as the JVM's own files do, is untouched. The second folder is made. Both can be written by any
     * user, so a container run later as another user can use the folder and clear what this one left.
     *
     * Edge cases:
     *
     * The link is removed, not followed: the folder it led to keeps its file.
     */
    withScratch {
      scratch =>
        val tmp = Files.createDirectories(scratch.resolve("tmp"))
        val beside = Files.writeString(tmp.resolve("hsperfdata"), "the JVM's own")
        val outside = Files.createDirectories(scratch.resolve("outside"))
        val kept = Files.writeString(outside.resolve("kept"), "not the server's")

        val own = Files.createDirectories(tmp.resolve("altitude"))
        Files.writeString(own.resolve("undertow123upload"), "a spooled upload")
        Files.writeString(Files.createDirectories(own.resolve("apache-tika-1")).resolve("page"), "a parser's file")
        Files.createSymbolicLink(own.resolve("link"), outside)

        TempFolder.prepare(own, own = true) shouldBe 3
        entriesOf(own) shouldBe Nil
        Files.readString(beside) shouldBe "the JVM's own"
        Files.readString(kept) shouldBe "not the server's"

        val missing = tmp.resolve("not-yet")
        TempFolder.prepare(missing, own = true) shouldBe 0
        Files.isDirectory(missing) shouldBe true

        for (dir <- Seq(own, missing))
          PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwxrwxrwx"
    }
  }

  test("A temp folder that is not the server's own is left as it is") {

    /**
     * Setup:
     *
     * A scratch folder holding another program's file, standing for the system's shared temp folder, which is the JVM's temp
     * folder wherever nothing says otherwise. Then a folder that does not exist, and a file system's root.
     *
     * Assertions:
     *
     * Prepared without the say-so, the folder keeps its file and nothing is removed, and the missing folder is not made. A file
     * system's root is refused even with the say-so: nothing is ever emptied there.
     */
    withScratch {
      scratch =>
        val shared = Files.createDirectories(scratch.resolve("shared"))
        val theirs = Files.writeString(shared.resolve("another-programs"), "in use")

        TempFolder.prepare(shared, own = false) shouldBe 0
        Files.readString(theirs) shouldBe "in use"

        val missing = scratch.resolve("missing")
        TempFolder.prepare(missing, own = false) shouldBe 0
        Files.exists(missing) shouldBe false

        val refusal = the[IllegalStateException] thrownBy TempFolder.prepare(scratch.getRoot, own = true)
        refusal.getMessage should include(TempFolder.OWN)
    }
  }
}
