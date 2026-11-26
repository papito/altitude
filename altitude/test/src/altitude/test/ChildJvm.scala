package altitude.test

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A second JVM with the test JVM's classpath, for what a test cannot do in its own: a crash, an exit, another environment */
object ChildJvm {

  /**
   * A second JVM that [[start]] started, and its output, stdout and stderr together, in a file. The output goes to a file, not a
   * pipe: reading a pipe to its end returns only when the JVM exits, so one that hangs would never reach a timeout.
   */
  final class Started private[ChildJvm] (val process: Process, log: Path) extends AutoCloseable {

    /** What the JVM has written so far */
    def output: String = new String(Files.readAllBytes(log))

    /**
     * Waits for the JVM to exit and answers its exit code and its output. A JVM still running at `timeout` is killed, with every
     * process it started, and fails the test.
     */
    def await(timeout: FiniteDuration): (Int, String) = {
      val exited = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
      if (!exited) {
        kill()
        // The kill is asynchronous: what the JVM wrote is read once it is gone
        process.waitFor(10, TimeUnit.SECONDS)
      }
      val written = output
      withClue(s"The second JVM exited within $timeout: ") {
        withClue(written) {
          exited shouldBe true
        }
      }
      (process.exitValue(), written)
    }

    /** Kills the JVM and every process it started, which a JVM that relaunches itself would otherwise leave running */
    def kill(): Unit = {
      process.descendants().forEach(descendant => descendant.destroyForcibly(): Unit)
      process.destroyForcibly(): Unit
    }

    /**
     * Kills what is still running and deletes the output, once every process that could hold it open has ended: a kill only
     * begins the end, and Windows deletes no file a process still has open
     */
    override def close(): Unit = {
      val holders = process.toHandle +: process.descendants().iterator().asScala.toSeq
      try kill()
      finally {
        holders.foreach(holder => Try(holder.onExit().get(10, TimeUnit.SECONDS)))
        Files.deleteIfExists(log): Unit
      }
    }
  }

  /**
   * Runs a main class in a second JVM with this one's classpath and environment, the environment changed by `environment`, and
   * answers its exit code and its output, stdout and stderr together. A crash of that JVM fails one test, where a crash of this
   * one would take every suite of the bundle with it; the crash's report goes to the output, and no core dump is written. A JVM
   * still running at `timeout` is killed and fails the test too.
   *
   * @param directory
   *   the second JVM's working directory; this JVM's when none
   * @param jvmOptions
   *   options of the second JVM itself. It finds `logback-test.xml` on the classpath it shares with this one, which silences the
   *   application: `-Dlogback.configurationFile=logback.xml` makes it log as a server does.
   */
  def run(
      mainClass: String,
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit = _ => (),
      timeout: FiniteDuration = 120.seconds,
      directory: Option[Path] = None,
      jvmOptions: Seq[String] = Seq.empty): (Int, String) = {
    val started = start(mainClass, arguments, environment, directory, jvmOptions)
    try started.await(timeout)
    finally started.close()
  }

  /**
   * Starts a main class in a second JVM as [[run]] does, and leaves it running: the caller waits for it, or stops it, and closes
   * it, which kills what is still running.
   */
  def start(
      mainClass: String,
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit = _ => (),
      directory: Option[Path] = None,
      jvmOptions: Seq[String] = Seq.empty): Started = {
    val classpath = getClass.getClassLoader match {
      // The test runner loads the tests through a class loader of its own
      case loader: URLClassLoader => loader.getURLs.map(url => Paths.get(url.toURI).toString).mkString(File.pathSeparator)
      case _ => System.getProperty("java.class.path")
    }
    startWith(Seq("-cp", classpath, mainClass), arguments, environment, directory, jvmOptions)
  }

  /** Starts a jar in a second JVM with `java -jar`, which reads its manifest, as [[start]] starts a main class */
  def startJar(
      jar: Path,
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit = _ => (),
      jvmOptions: Seq[String] = Seq.empty): Started =
    startWith(Seq("-jar", jar.toString), arguments, environment, None, jvmOptions)

  /** Starts `java` with what it runs (a main class on a classpath, or a jar) after the JVM's options, and then the arguments */
  private def startWith(
      runs: Seq[String],
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit,
      directory: Option[Path],
      jvmOptions: Seq[String]): Started = {
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(java, "-XX:+ErrorFileToStderr", "-XX:-CreateCoredumpOnCrash") ++ jvmOptions ++ runs ++ arguments

    val log = Files.createTempFile("child-jvm", ".log")
    try {
      val builder = new ProcessBuilder(command*).redirectErrorStream(true).redirectOutput(log.toFile)
      directory.foreach(dir => builder.directory(dir.toFile))
      environment(builder.environment())
      new Started(builder.start(), log)
    } catch {
      case failure: Throwable =>
        Files.deleteIfExists(log)
        throw failure
    }
  }
}
