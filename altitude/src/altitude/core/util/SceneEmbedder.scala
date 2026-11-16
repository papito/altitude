package altitude.core.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.control.NonFatal

import altitude.core.ImageException
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneFit
import altitude.core.models.SceneModel

/** Embeds a still image with every [[altitude.core.models.SceneModel]]; a model that cannot embed it gives none */
trait SceneEmbedder extends AutoCloseable:

  /** `image` as `ImageUtil.matFromBytes` decodes it, upright; `name` is for the log */
  def embed(image: Mat, name: String): SceneEmbeddings

/**
 * The process's one ONNX Runtime environment, which every session and tensor of the embedders is made in.
 *
 * ONNX Runtime's official builds send usage telemetry to Microsoft unless told not to (`Privacy.md` in its jar). Altitude runs
 * with it off. The one switch that turns all of it off is the `ORT_DISABLE_TELEMETRY=1` environment variable, which the native
 * library reads as it initializes: with it, no device ID and no event store are written under the home folder's cache
 * (`Microsoft/DeveloperTools/.onnxruntime`), and the process makes no connection to the collector. `forkEnv` in `build.mill` sets
 * it for every JVM Mill forks; a jar started any other way needs it in its environment.
 *
 * The Java API's switch is called here too, but it comes too late to stand in for the variable: by the time an environment
 * exists, its creation has written the device ID, stored an event describing the machine and started the uploader, which goes on
 * trying to reach the collector. What the call still stops is, by `Privacy.md`, the events that are not essential. So a start
 * without the variable is logged at WARN.
 */
private[core] object OnnxEnvironment:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The variable ONNX Runtime's native library reads as it initializes, and the value that switches its telemetry off */
  val TELEMETRY_OFF: (String, String) = "ORT_DISABLE_TELEMETRY" -> "1"

  lazy val get: OrtEnvironment =
    val environment = withoutTelemetry(OrtEnvironment.getEnvironment, sys.env.get(TELEMETRY_OFF._1))
    retain(environment)
    environment

  /**
   * Takes a second reference to the native environment, which is never given back.
   *
   * ONNX Runtime's Java API registers a JVM shutdown hook of its own (`OrtEnvironment.OrtEnvCloser`) that releases the
   * environment. The JVM starts every hook at once, so on a SIGTERM this one runs beside Pekko's coordinated shutdown, whose
   * `Altitude.cleanup()` is still embedding what the import queue accepted. A pass on a released environment fails: a session's
   * first pass reaches for ONNX Runtime's default logger, which is gone, and with several threads embedding the JVM dies of a
   * SIGSEGV in ONNX Runtime's library, the Import's files left pending. The native environment is one per process and counts its
   * references (the C API's `CreateEnv` takes one, `ReleaseEnv` gives one back), so with a second reference the hook's release
   * leaves the environment alive under every session opened in it, and the end of the process frees it.
   *
   * The API offers no way to take a reference, nor to drop its hook. So this calls `OrtEnvironment`'s private native
   * `createHandle`, its `CreateEnv`, by reflection, with the API handle in the package-private `OnnxRuntime.ortApiHandle`, and
   * checks that the handle it is answered is the environment's own. An ONNX Runtime upgrade that renames one of those members, or
   * answers another environment, fails the start here, naming the version: the other choice is a server that crashes at its next
   * stop under load. One that stops counting references is caught by the test alone. The guard is `OnnxEnvironmentTests`'
   * shutdown hook test, which releases the environment as the hook does and then runs a session's first pass.
   */
  private def retain(environment: OrtEnvironment): Unit =
    def broken(why: String, cause: Throwable | Null): IllegalStateException =
      IllegalStateException(
        s"ONNX Runtime ${environment.getVersion} gives no second reference to its environment ($why), so its shutdown hook " +
          "would release the environment under the sessions a stop is still draining. OnnxEnvironment.retain needs adapting " +
          "to this version",
        cause
      )

    val handle =
      try
        val api = Class.forName("ai.onnxruntime.OnnxRuntime").getDeclaredField("ortApiHandle")
        api.setAccessible(true)
        val own = classOf[OrtEnvironment].getDeclaredField("nativeHandle")
        own.setAccessible(true)
        val createHandle =
          classOf[OrtEnvironment].getDeclaredMethod("createHandle", java.lang.Long.TYPE, Integer.TYPE, classOf[String])
        createHandle.setAccessible(true)
        // An environment that exists keeps its own level and name; these are the Java API's defaults
        val level = Integer.valueOf(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING.getValue)
        createHandle.invoke(null, api.get(null), level, OrtEnvironment.DEFAULT_NAME) -> own.get(environment)
      catch case e: ReflectiveOperationException => throw broken(e.toString, e)

    if handle._1 != handle._2 then throw broken(s"createHandle answered environment ${handle._1}, not ${handle._2}", null)
    logger.debug("Holding a second reference to ONNX Runtime's environment, which its shutdown hook's release leaves alive")

  /** `environment` with the API's telemetry switch off, after a WARN when `variable`, the process's own, left the rest on */
  def withoutTelemetry(environment: OrtEnvironment, variable: Option[String]): OrtEnvironment =
    if !variable.contains(TELEMETRY_OFF._2) then
      logger.warn(
        s"ONNX Runtime's telemetry is on: ${TELEMETRY_OFF._1} is not ${TELEMETRY_OFF._2} in this process's environment, so it " +
          "keeps a device ID under the home folder's cache and sends usage events to Microsoft. " +
          s"Set ${TELEMETRY_OFF._1}=${TELEMETRY_OFF._2} before the start to switch it off")
    environment.setTelemetry(false)
    environment

private[util] object OnnxSessions:

  /**
   * One intra-op and one inter-op thread: the parallelism is the callers' (the import's threads or a search's), and concurrent
   * passes each spreading over every core would thrash. A session is thread-safe, so its callers share it.
   */
  def open(environment: OrtEnvironment, path: Path): OrtSession =
    val options = new OrtSession.SessionOptions()
    try
      options.setIntraOpNumThreads(1)
      options.setInterOpNumThreads(1)
      environment.createSession(path.toString, options)
    finally options.close()

  /** The output row, L2-normalized so two embeddings compare by their dot product. The tensor stays the caller's to close. */
  def embed(session: OrtSession, input: String, tensor: OnnxTensor, model: SceneModel): SceneEmbedding =
    val result = session.run(Collections.singletonMap(input, tensor))
    try
      val output = result.get(0).asInstanceOf[OnnxTensor].getFloatBuffer
      if output.remaining() != model.dimensions then
        throw IllegalStateException(s"Scene model ${model.name} gave ${output.remaining()} values, not ${model.dimensions}")
      val values = new Array[Float](model.dimensions)
      output.get(values)
      SceneEmbedding(IArray.unsafeFromArray(unit(values)))
    finally result.close()

  /** In place; a zero vector has no direction and is a failed pass */
  private def unit(values: Array[Float]): Array[Float] =
    var sum = 0.0
    var i = 0
    while i < values.length do
      sum += values(i).toDouble * values(i)
      i += 1
    if sum == 0.0 then throw IllegalStateException("The model gave a zero vector")
    val norm = Math.sqrt(sum).toFloat
    i = 0
    while i < values.length do
      values(i) /= norm
      i += 1
    values

object OnnxSceneEmbedder:
  private val CHANNELS = 3

  /** The name of the Scene models' one input in their exports */
  private val INPUT = "image"

  /**
   * The model's normalization folded into one multiply and add per channel (`(byte / 255 - mean) / std` is
   * `byte * scale + offset`), so the hot loop does no division
   */
  private class ModelInput(model: SceneModel):
    val pixels: Int = model.inputSize * model.inputSize
    val shape: Array[Long] = Array(1L, CHANNELS, model.inputSize, model.inputSize)
    val scale: IArray[Float] = model.std.map(std => 1f / (255f * std))
    val offset: IArray[Float] = model.mean.zip(model.std).map((mean, std) => -mean / std)

  /** The image fitted to the model's square as packed RGB bytes, row-major: the input before normalization */
  private[core] def prepare(image: Mat, model: SceneModel): Array[Byte] =
    val side = model.inputSize
    model.fit match
      case SceneFit.CenterCrop =>
        val fitted = fitShortSide(image, side)
        try
          // A view of the square in the middle of the fitted image
          val square = new Mat(fitted, new Rect((fitted.width() - side) / 2, (fitted.height() - side) / 2, side, side))
          try rgbBytes(square)
          finally square.release()
        finally fitted.release()
      case SceneFit.Squash =>
        val squashed = resized(image, side, side)
        try rgbBytes(squashed)
        finally squashed.release()

  /** The long side never rounds below `side`, so a square of the side fits inside */
  private def fitShortSide(image: Mat, side: Int): Mat =
    val scale = side.toDouble / Math.min(image.width(), image.height())
    val width = Math.max(side, Math.round(image.width() * scale).toInt)
    val height = Math.max(side, Math.round(image.height() * scale).toInt)
    resized(image, width, height)

  /**
   * `INTER_AREA` when neither side is enlarged, which averages the pixels it drops where bicubic aliases a large photo;
   * `INTER_CUBIC` when a side is, which `INTER_AREA` does not interpolate
   */
  private def resized(image: Mat, width: Int, height: Int): Mat =
    val interpolation = if width <= image.width() && height <= image.height() then Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
    val out = new Mat()
    try Imgproc.resize(image, out, new Size(width, height), 0, 0, interpolation)
    catch
      case e: Throwable =>
        out.release()
        throw e
    out

  private def rgbBytes(fitted: Mat): Array[Byte] =
    val code = fitted.channels() match
      case 1 => Imgproc.COLOR_GRAY2RGB
      case 3 => Imgproc.COLOR_BGR2RGB
      case 4 => Imgproc.COLOR_BGRA2RGB
      case channels => throw ImageException(s"An image of $channels channels cannot be embedded")
    // The conversion's output is continuous, where a center square is a view into its fitted image
    val rgb = new Mat()
    try
      Imgproc.cvtColor(fitted, rgb, code)
      val bytes = new Array[Byte](fitted.rows() * fitted.cols() * CHANNELS)
      rgb.get(0, 0, bytes)
      bytes
    finally rgb.release()

/**
 * One `OrtSession` per Scene model, shared by every thread. The sessions open when the class is built, so a model that fails to
 * open fails the start, never an import: a library imported without embeddings would fall back to the hash silently.
 *
 * Each model fits the image its own way (`SceneModel.fit`). DINOv2's center square (the short side to 224, then a center crop) is
 * this project's transform, not the checkpoint's (short side to 256 bicubic, crop 224). SigLIP 2 squashes the whole image to 256
 * x 256, as its checkpoint's processor does, so a wide frame's edges stay in view. The export script checks the models on these
 * fits and the Settings' similarity thresholds are calibrated to them: a change here means calibrating again.
 *
 * The memory and pass times are measured in [[ImportParallelism]].
 */
class OnnxSceneEmbedder(models: ModelFiles) extends SceneEmbedder:
  import OnnxSceneEmbedder.*

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val environment: OrtEnvironment = OnnxEnvironment.get

  private val sessions: Map[SceneModel, OrtSession] = openSessions()

  private val inputs: Map[SceneModel, ModelInput] = SceneModel.values.map(model => model -> ModelInput(model)).toMap

  private val closed = AtomicBoolean(false)

  /** When a later session fails to open, the ones open already are closed first */
  private def openSessions(): Map[SceneModel, OrtSession] =
    val opened = mutable.LinkedHashMap.empty[SceneModel, OrtSession]
    try
      SceneModel.values.foreach(model => opened(model) = openSession(model))
      opened.toMap
    catch
      case e: Throwable =>
        opened.values.foreach {
          session =>
            try session.close()
            catch case NonFatal(suppressed) => e.addSuppressed(suppressed)
        }
        throw e

  private def openSession(model: SceneModel): OrtSession =
    val path = models.path(model.file)
    val session = OnnxSessions.open(environment, path)
    logger.info(s"Opened the ONNX session of Scene model ${model.name} from $path")
    session

  /** A model that cannot prepare the image or whose pass fails gives no embedding (WARN), and the other model's stands */
  override def embed(image: Mat, name: String): SceneEmbeddings =
    SceneEmbeddings(dinov2 = embed(image, SceneModel.Dinov2, name), siglip2 = embed(image, SceneModel.Siglip2, name))

  private def embed(image: Mat, model: SceneModel, name: String): Option[SceneEmbedding] =
    try Some(run(prepare(image, model), model))
    catch
      case NonFatal(e) =>
        logger.warn(s"Scene model ${model.name} could not embed $name", e)
        None

  private def run(rgb: Array[Byte], model: SceneModel): SceneEmbedding =
    val input = inputs(model)
    val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalize(rgb, input)), input.shape)
    try OnnxSessions.embed(sessions(model), INPUT, tensor, model)
    finally tensor.close()

  private def normalize(rgb: Array[Byte], input: ModelInput): Array[Float] =
    val pixels = input.pixels
    val values = new Array[Float](CHANNELS * pixels)
    var channel = 0
    while channel < CHANNELS do
      val scale = input.scale(channel)
      val offset = input.offset(channel)
      val plane = channel * pixels
      var pixel = 0
      while pixel < pixels do
        values(plane + pixel) = (rgb(pixel * CHANNELS + channel) & 0xff) * scale + offset
        pixel += 1
      channel += 1
    values

  /** Once, since ONNX Runtime warns on a second close of a session; the environment is the process's and stays */
  override def close(): Unit = if closed.compareAndSet(false, true) then sessions.values.foreach(_.close())
