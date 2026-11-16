package altitude.core.unit

import ai.onnxruntime.OrtEnvironment
import altitude.test.TestFocus
import altitude.test.TestModelFiles
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*

import altitude.core.util.ModelFile
import altitude.core.util.OnnxEnvironment

/**
 * ONNX Runtime's telemetry is off wherever the build starts a JVM. The state with the variable missing is never run here: it
 * would write a device ID under the home folder and try to reach Microsoft's collector.
 */
@DoNotDiscover class OnnxEnvironmentTests extends AnyFunSuite with TestFocus {

  private val (variable, off) = OnnxEnvironment.TELEMETRY_OFF

  test("The embedders' environment is the process's one, with the API's telemetry switch off") {

    /**
     * Setup:
     *
     * The environment the embedders take, and a stand-in environment handed to what prepares it.
     *
     * Mocks:
     *
     * The stand-in is a mock, since an environment has no way to ask whether its telemetry is on.
     *
     * Assertions:
     *
     * The embedders' environment is ONNX Runtime's one environment of the process, and preparing an environment switches its
     * telemetry off through the API and answers the same environment.
     *
     * Edge cases:
     *
     * The switch is thrown whether or not the variable is set, which only decides the WARN.
     */
    OnnxEnvironment.get shouldBe theSameInstanceAs(OrtEnvironment.getEnvironment)

    Seq(Some(off), None).foreach {
      value =>
        val environment = mock(classOf[OrtEnvironment])
        OnnxEnvironment.withoutTelemetry(environment, value) shouldBe theSameInstanceAs(environment)
        verify(environment).setTelemetry(false)
    }
  }

  test("A JVM the build starts has the telemetry variable, and with it ONNX Runtime writes nothing under the home folder") {

    /**
     * Setup:
     *
     * This JVM, forked by the build, and a second JVM started from it with the same classpath and environment but an empty
     * scratch folder as its home. The second obtains the embedders' environment and opens a session on the face detection model.
     *
     * Assertions:
     *
     * This JVM's environment has the variable at the value that switches ONNX Runtime's telemetry off. The second JVM opens its
     * session and exits cleanly, and its home folder is still empty: no device ID and no event store.
     */
    sys.env.get(variable) shouldBe Some(off)

    val home = Files.createTempDirectory("onnx-home")
    val classpath = getClass.getClassLoader match {
      // The test runner loads the tests through a class loader of its own
      case loader: URLClassLoader => loader.getURLs.map(url => Paths.get(url.toURI).toString).mkString(File.pathSeparator)
      case _ => System.getProperty("java.class.path")
    }
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val model = TestModelFiles.models.path(ModelFile.YuNet).toString
    val probe = OnnxEnvironmentProbe.getClass.getName.stripSuffix("$")
    val builder = new ProcessBuilder(java, "-cp", classpath, probe, model).redirectErrorStream(true)
    builder.environment().put("HOME", home.toString)
    // Where the cache is looked for first
    builder.environment().remove("XDG_CACHE_HOME")

    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes())
    withClue(output) {
      process.waitFor(60, TimeUnit.SECONDS) shouldBe true
      process.exitValue() shouldBe 0
      output should include(OnnxEnvironmentProbe.OPENED)
    }

    val written = Files.walk(home).iterator().asScala.filter(_ != home).toList
    written shouldBe empty
  }
}

/** What the second JVM of [[OnnxEnvironmentTests]] runs: the embedders' environment, and a session on the model file named */
object OnnxEnvironmentProbe {
  val OPENED = "Session opened"

  def main(args: Array[String]): Unit = {
    val session = OnnxEnvironment.get.createSession(args(0))
    try println(s"$OPENED: ${session.getInputNames}")
    finally session.close()
  }
}
