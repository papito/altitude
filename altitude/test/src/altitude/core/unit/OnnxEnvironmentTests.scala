package altitude.core.unit

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import altitude.test.ChildJvm
import altitude.test.TestFocus
import altitude.test.TestModelFiles
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import org.apache.commons.io.FileUtils
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Using

import altitude.core.models.SceneModel
import altitude.core.util.ModelFile
import altitude.core.util.OnnxEnvironment

/**
 * ONNX Runtime's telemetry is off wherever the build starts a JVM, and its environment outlives ONNX Runtime's own release of it.
 * The state with the telemetry variable missing is never run here: it would write a device ID under the home folder and try to
 * reach Microsoft's collector.
 */
@DoNotDiscover class OnnxEnvironmentTests extends AnyFunSuite with TestFocus {

  private val (variable, off) = OnnxEnvironment.TELEMETRY_OFF

  /** Runs a probe object's `main` in a second JVM (`ChildJvm.run`): its exit code and its output */
  private def runProbe(
      probe: AnyRef,
      arguments: Seq[String],
      environment: java.util.Map[String, String] => Unit = _ => (),
      timeout: FiniteDuration = 120.seconds): (Int, String) =
    ChildJvm.run(probe.getClass.getName.stripSuffix("$"), arguments, environment, timeout)

  private def model(file: ModelFile): Path = TestModelFiles.models.path(file)

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
    try {
      val (exitCode, output) = runProbe(
        OnnxEnvironmentProbe,
        Seq(model(ModelFile.YuNet).toString),
        environment => {
          environment.put("HOME", home.toString)
          // Where the cache is looked for first
          environment.remove("XDG_CACHE_HOME")
        }
      )
      withClue(output) {
        exitCode shouldBe 0
        output should include(OnnxEnvironmentProbe.OPENED)
      }

      val written = Using.resource(Files.walk(home))(_.iterator().asScala.filter(_ != home).toList)
      written shouldBe empty
    } finally FileUtils.deleteDirectory(home.toFile)
  }

  test("A session's first pass succeeds after ONNX Runtime's shutdown hook has released the environment") {

    /**
     * Setup:
     *
     * A second JVM that obtains the embedders' environment, opens a session on a Scene model, and then does what ONNX Runtime's
     * own JVM shutdown hook does the moment a stop begins: releases the environment through `OrtEnvironment`'s private `close`.
     * Only then does it make a tensor and run the session's first pass, as the drain of a stop does for an image the import queue
     * had accepted.
     *
     * Assertions:
     *
     * The pass answers the model's embedding and the JVM exits cleanly, its real shutdown hook releasing the environment once
     * more on the way out: the reference `OnnxEnvironment` holds kept the environment alive under the session.
     *
     * Edge cases:
     *
     * Without that reference the release frees the environment and the pass fails: here by an ONNX Runtime error for its missing
     * default logger, and in a server, where several threads embed at once, by a SIGSEGV in ONNX Runtime's library. The second
     * JVM's output, in the failure's clue, is what a regression looks like. The probe reaches ONNX Runtime's private members by
     * name: an ONNX Runtime upgrade that renames them fails here too, by its exception.
     */
    val scene = SceneModel.Dinov2
    val (exitCode, output) = runProbe(OnnxEnvironmentReleaseProbe, Seq(model(scene.file).toString, scene.inputSize.toString))
    withClue(output) {
      output should include(s"${OnnxEnvironmentReleaseProbe.PASSED}: ${scene.dimensions} values")
      exitCode shouldBe 0
    }
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

/**
 * What the second JVM of the shutdown hook test runs: a session on the Scene model file named, the environment released as ONNX
 * Runtime's shutdown hook releases it, and then the session's first pass over a blank image of the size named
 */
object OnnxEnvironmentReleaseProbe {
  val PASSED = "First pass after the release"

  private val CHANNELS = 3

  def main(args: Array[String]): Unit = {
    val environment = OnnxEnvironment.get
    val session = environment.createSession(args(0))
    val size = args(1).toInt

    release(environment)

    val tensor =
      OnnxTensor.createTensor(environment, FloatBuffer.allocate(CHANNELS * size * size), Array(1L, CHANNELS, size, size))
    val result = session.run(Collections.singletonMap(session.getInputNames.iterator.next, tensor))
    println(s"$PASSED: ${result.get(0).asInstanceOf[OnnxTensor].getFloatBuffer.remaining()} values")
  }

  /** `OrtEnvironment.OrtEnvCloser.run`, the hook's body: the private native `close` of the API's handle and the environment's */
  private def release(environment: OrtEnvironment): Unit = {
    val api = Class.forName("ai.onnxruntime.OnnxRuntime").getDeclaredField("ortApiHandle")
    api.setAccessible(true)
    val handle = classOf[OrtEnvironment].getDeclaredField("nativeHandle")
    handle.setAccessible(true)
    val close = classOf[OrtEnvironment].getDeclaredMethod("close", java.lang.Long.TYPE, java.lang.Long.TYPE)
    close.setAccessible(true)
    close.invoke(null, api.get(null), handle.get(environment))
  }
}
