package altitude.core.unit

import altitude.test.ChildJvm
import altitude.test.TestFocus
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*
import scala.util.Using

import altitude.core.Environment

/**
 * A start that fails, as a process: `altitude.core.App` run in a second JVM as prod, on SQLite in a scratch Data directory, with
 * the test run's model files and the prod logging configuration.
 */
@DoNotDiscover class AppStartTests extends AnyFunSuite with TestFocus {

  /** `App`'s exit code and output, started with `variables` over a scratch Data directory that `f` then looks into */
  private def startOf[T](variables: (String, String)*)(f: (Int, String, Path) => T): T = {
    val data = Files.createTempDirectory("app-start")
    try {
      val (exitCode, output) = ChildJvm.run(
        "altitude.core.App",
        Seq.empty,
        environment => {
          environment.put(Environment.Variable.ENV, Environment.Name.PROD)
          environment.put("ALTITUDE_DATA_DIR", data.toString)
          // A port nothing listens on, so a start that should have failed and did not never takes the default port
          environment.put("ALTITUDE_PORT", Using.resource(new ServerSocket(0))(_.getLocalPort).toString)
          variables.foreach((name, value) => environment.put(name, value))
        },
        // The prod logging configuration, in place of the test run's, which silences the application
        jvmOptions = Seq("-Dlogback.configurationFile=logback.xml")
      )
      withClue(output) {
        f(exitCode, output, data)
      }
    } finally FileUtils.deleteDirectory(data.toFile)
  }

  private def filesIn(dir: Path): List[Path] = Using.resource(Files.list(dir))(_.iterator().asScala.toList)

  test("A start whose port is taken ends the process with status 1 and its reason logged") {

    /**
     * Setup:
     *
     * A port of the loopback address that the test itself listens on, and a second JVM started as the server on that port. Its
     * start gets as far as a start can: the database, the models, the actor system, the queues and the startup tasks are all
     * running when the HTTP server fails to bind.
     *
     * Assertions:
     *
     * The process ends by itself, with status 1, and its log holds one ERROR line that says Altitude could not start and why. It
     * does not stay up with no server, kept alive by the actor system's threads.
     *
     * Edge cases:
     *
     * The instance the start had built is cleaned up on the way out: the exit runs the stop's cleanup.
     */
    Using.resource(new ServerSocket(0, 1, InetAddress.getLoopbackAddress)) {
      taken =>
        startOf("ALTITUDE_HOST" -> "127.0.0.1", "ALTITUDE_PORT" -> taken.getLocalPort.toString) {
          (exitCode, output, _) =>
            exitCode shouldBe 1
            output should include("ERROR")
            output should include("Altitude could not start: ")
            output should include("Address already in use")
            output should include("Cleaned up")
            (output should not).include("Altitude is listening")
        }
    }
  }

  test("A start with a port that is no port number ends the process before anything is opened") {

    /**
     * Setup:
     *
     * A second JVM started as the server with `ALTITUDE_PORT` set to a word, then to an empty value, then to a number that is no
     * port, each over an empty scratch Data directory.
     *
     * Assertions:
     *
     * Each process ends with status 1 and an ERROR line that says Altitude could not start, names the variable and quotes the
     * value. The Data directory is still empty: the port is checked as the instance is built, before a database is made, a model
     * is opened or the actor system exists.
     */
    Seq("eighty-eighty", "", "70000").foreach {
      port =>
        withClue(s"[$port]: ") {
          startOf("ALTITUDE_PORT" -> port) {
            (exitCode, output, data) =>
              exitCode shouldBe 1
              output should include("Altitude could not start: ")
              output should include("ALTITUDE_PORT")
              output should include(s"\"$port\"")
              filesIn(data) shouldBe empty
          }
        }
    }
  }

  test("A start with an empty host or an empty Data directory ends the process, naming the variable") {

    /**
     * Setup:
     *
     * A second JVM started as the server with `ALTITUDE_HOST` set but empty, over an empty scratch Data directory, and another
     * with `ALTITUDE_DATA_DIR` set but empty.
     *
     * Assertions:
     *
     * Each process ends with status 1 and an ERROR line that says Altitude could not start and names the variable. The first
     * never binds the loopback address, which is what an empty host is to the JDK, and its Data directory is still empty; the
     * second never takes the jar's own folder for its Data directory.
     */
    startOf("ALTITUDE_HOST" -> "") {
      (exitCode, output, data) =>
        exitCode shouldBe 1
        output should include("Altitude could not start: ")
        output should include("ALTITUDE_HOST")
        (output should not).include("Altitude is listening")
        filesIn(data) shouldBe empty
    }

    startOf("ALTITUDE_DATA_DIR" -> "") {
      (exitCode, output, _) =>
        exitCode shouldBe 1
        output should include("Altitude could not start: ")
        output should include("ALTITUDE_DATA_DIR")
        (output should not).include("Altitude is listening")
    }
  }

  test("A start refused for its environment ends the process with the refusal in a log line") {

    /**
     * Setup:
     *
     * A second JVM started as the server with no `altitude.env` property and an `ALTITUDE_ENV` that names no environment.
     *
     * Assertions:
     *
     * The process ends with status 1, and the refusal, which is thrown while `Environment` initializes, is in an ERROR line of
     * the log with the accepted values, not only in the stack trace of an `ExceptionInInitializerError` on stderr.
     */
    startOf(Environment.Variable.ENV -> "dve") {
      (exitCode, output, data) =>
        exitCode shouldBe 1
        output should include(
          """Altitude could not start: The ALTITUDE_ENV environment variable is "dve", which names no environment""")
        output should include(Environment.ACCEPTED.mkString(", "))
        filesIn(data) shouldBe empty
    }
  }
}
