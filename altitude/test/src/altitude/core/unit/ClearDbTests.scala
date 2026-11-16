package altitude.core.unit

import altitude.test.ChildJvm
import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Const
import altitude.core.Environment

/** `make clear-db` (`altitude.tools.clearDb`), run as the Makefile runs it: a JVM of its own, as dev */
@DoNotDiscover class ClearDbTests extends AnyFunSuite with TestFocus {

  /** A file with some content under `dir`, its folders made */
  private def fileIn(dir: Path, name: String): Path = {
    Files.createDirectories(dir)
    Files.writeString(dir.resolve(name), "content")
  }

  test("clear-db clears what application-dev.conf names, whatever the shell's ALTITUDE_ variables name") {

    /**
     * Setup:
     *
     * A scratch folder as the working directory of a second JVM run as dev. Its `application-dev.conf` names a Data directory on
     * SQLite, which holds a database file and a Library's stored file. The second JVM's environment names another target, as a
     * shell left pointing at another instance does: PostgreSQL at an address nothing listens on, and a second Data directory with
     * a database file and a stored file of its own.
     *
     * Assertions:
     *
     * clear-db exits cleanly, having emptied the database folder and the Libraries' folder of the config file's Data directory.
     * The other Data directory's files are as they were, and nothing reached for the PostgreSQL address: the variables steer
     * nothing.
     *
     * Edge cases:
     *
     * The two emptied folders themselves stay.
     */
    val scratch = Files.createTempDirectory("clear-db")
    try {
      val root = Files.createDirectories(scratch.resolve("root"))
      val ofFile = scratch.resolve("data-of-file")
      val ofVariables = scratch.resolve("data-of-variables")
      // Forward slashes whatever the platform: a backslash is an escape in a quoted HOCON string
      Files.writeString(
        root.resolve("application-dev.conf"),
        s"""${Const.Conf.FS_DATA_DIR}="${ofFile.toString.replace('\\', '/')}"\n${Const.Conf.DB_ENGINE}=${Const.DbEngineName.SQLITE}\n"""
      )

      val Seq(database, otherDatabase) = Seq(ofFile, ofVariables).map(data => fileIn(data.resolve("db"), "altitude.db"))
      val Seq(stored, otherStored) =
        Seq(ofFile, ofVariables).map(data => fileIn(data.resolve(Const.DataStore.REPOSITORIES).resolve("a-library"), "a-file"))

      val (exitCode, output) = ChildJvm.run(
        "altitude.tools.clearDb",
        Seq.empty,
        environment => {
          environment.put(Environment.Variable.ENV, Environment.Name.DEV)
          environment.put("ALTITUDE_DATA_DIR", ofVariables.toString)
          environment.put("ALTITUDE_DB_ENGINE", Const.DbEngineName.POSTGRES)
          environment.put("ALTITUDE_DB_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:1/elsewhere")
          environment.put("ALTITUDE_DB_POSTGRES_PASSWORD", "a-password")
        },
        directory = Some(root)
      )

      withClue(output) {
        exitCode shouldBe 0

        Files.exists(database) shouldBe false
        Files.exists(stored.getParent) shouldBe false
        Files.isDirectory(database.getParent) shouldBe true
        Files.isDirectory(ofFile.resolve(Const.DataStore.REPOSITORIES)) shouldBe true

        Files.readString(otherDatabase) shouldBe "content"
        Files.readString(otherStored) shouldBe "content"
      }
    } finally FileUtils.deleteDirectory(scratch.toFile)
  }
}
