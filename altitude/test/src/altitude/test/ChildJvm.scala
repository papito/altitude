package altitude.test

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.scalatest.Assertions.withClue
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** A second JVM with the test JVM's classpath, for what a test cannot do in its own: a crash, an exit, another environment */
object ChildJvm {

  /**
   * Runs a main class in a second JVM with this one's classpath and environment, the environment changed by `environment`, and
   * answers its exit code and its output, stdout and stderr together. A crash of that JVM fails one test, where a crash of this
   * one would take every suite of the bundle with it; the crash's report goes to the output, and no core dump is written. A JVM
   * still running at `timeout` is killed and fails the test too.
   *
   * @param directory
   *   the second JVM's working directory; this JVM's when none
   */
  def run(
      mainClass: String,
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit = _ => (),
      timeout: FiniteDuration = 120.seconds,
      directory: Option[Path] = None): (Int, String) = {
    val classpath = getClass.getClassLoader match {
      // The test runner loads the tests through a class loader of its own
      case loader: URLClassLoader => loader.getURLs.map(url => Paths.get(url.toURI).toString).mkString(File.pathSeparator)
      case _ => System.getProperty("java.class.path")
    }
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(java, "-XX:+ErrorFileToStderr", "-XX:-CreateCoredumpOnCrash", "-cp", classpath, mainClass) ++ arguments

    // The output goes to a file, not a pipe: reading a pipe to its end returns only when the JVM exits, so one that hangs would
    // never reach the timeout
    val log = Files.createTempFile("child-jvm", ".log")
    try {
      val builder = new ProcessBuilder(command*).redirectErrorStream(true).redirectOutput(log.toFile)
      directory.foreach(dir => builder.directory(dir.toFile))
      environment(builder.environment())

      val process = builder.start()
      val exited = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
      if (!exited) {
        process.destroyForcibly()
        // The kill is asynchronous: what the JVM wrote is read once it is gone
        process.waitFor(10, TimeUnit.SECONDS)
      }
      val output = new String(Files.readAllBytes(log))
      withClue(s"The second JVM exited within $timeout: ") {
        withClue(output) {
          exited shouldBe true
        }
      }
      (process.exitValue(), output)
    } finally Files.deleteIfExists(log): Unit
  }
}
