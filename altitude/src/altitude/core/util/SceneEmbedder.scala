package altitude.core.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
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

/**
 * What embeds a still image for every [[altitude.core.models.SceneModel]]: the image's Scene embeddings, each model's or none
 * where that model could not embed it. Closing it releases what the models hold.
 */
trait SceneEmbedder extends AutoCloseable:

  /**
   * The embeddings of an image as `ImageUtil.matFromBytes` decodes it (gray, BGR or BGRA), upright; `name` (the file's) is for
   * the log
   */
  def embed(image: Mat, name: String): SceneEmbeddings

/** What the ONNX Runtime embedders share: how a model's session is opened, and how a pass's output becomes an embedding */
private[util] object OnnxSessions:

  /**
   * A session over the model file at the path, with one intra-op and one inter-op thread: the parallelism is the callers', the
   * import's threads or a search's, and concurrent passes each spreading over every core would thrash. A session is thread-safe,
   * so its callers share it.
   */
  def open(environment: OrtEnvironment, path: Path): OrtSession =
    val options = new OrtSession.SessionOptions()
    try
      options.setIntraOpNumThreads(1)
      options.setInterOpNumThreads(1)
      environment.createSession(path.toString, options)
    finally options.close()

  /**
   * One pass of a model's session over its one input, the tensor under the input's name: the output row, L2-normalized, as the
   * model's embedding. The tensor stays the caller's to close.
   */
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

  /** The vector scaled to unit length, in place; a zero vector has no direction and is a failed pass */
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
   * What a model's input tensor is built with: its NCHW shape, and the model's normalization folded into one multiply and add per
   * channel (`(byte / 255 - mean) / std` is `byte * scale + offset`), so the hot loop does no division
   */
  private class ModelInput(model: SceneModel):
    val pixels: Int = model.inputSize * model.inputSize
    val shape: Array[Long] = Array(1L, CHANNELS, model.inputSize, model.inputSize)
    val scale: IArray[Float] = model.std.map(std => 1f / (255f * std))
    val offset: IArray[Float] = model.mean.zip(model.std).map((mean, std) => -mean / std)

  /**
   * The image fitted to the model's square (`SceneModel.fit`) as packed RGB bytes, row-major: the model's input before
   * normalization. The image is as `ImageUtil.matFromBytes` decodes it (gray, BGR or BGRA) and is left as it is.
   */
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

  /**
   * The image with its short side at the side, the long side scaled by the same factor and never below it after rounding, so a
   * square of the side fits inside
   */
  private def fitShortSide(image: Mat, side: Int): Mat =
    val scale = side.toDouble / Math.min(image.width(), image.height())
    val width = Math.max(side, Math.round(image.width() * scale).toInt)
    val height = Math.max(side, Math.round(image.height() * scale).toInt)
    resized(image, width, height)

  /**
   * The image at the size, its aspect kept only if the size keeps it: `INTER_AREA` when neither side is enlarged, which averages
   * the pixels it drops where bicubic aliases a large photo, and `INTER_CUBIC` when a side is, which `INTER_AREA` does not
   * interpolate
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

  /** A fitted image's pixels as packed RGB bytes, whatever `ImageUtil` decoded the image as */
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
 * The Scene embeddings of a still image, computed by every [[altitude.core.models.SceneModel]] through ONNX Runtime.
 *
 * The process has one `OrtEnvironment` and this class opens one `OrtSession` per Scene model when it is built, from the model's
 * file among the model files (`SceneModel.file`), so a model that fails to open fails the start and never an import (a library
 * imported without embeddings would fall back to the hash silently). An `OrtSession` is thread-safe, so every import thread runs
 * the same two; each session is opened with one intra-op and one inter-op thread (`OnnxSessions.open`), since the parallelism is
 * the import threads' and twelve concurrent passes each spreading over every core would thrash.
 *
 * The preprocessing is each model's own (`SceneModel.inputSize` and `fit`), over the one decoded image. DINOv2 takes the center
 * square: the short side fitted to 224 and a center crop, this project's transform rather than the checkpoint's (its processor
 * resizes the short side to 256 bicubic and crops 224). SigLIP 2 takes the whole image resized to 256 x 256 with its aspect not
 * kept, as its checkpoint's processor does, so the edges of a wide frame, which the center square leaves out, stay in view.
 * Either is then converted to RGB, scaled to 0..1 and normalized per channel with the model's mean and std, laid out NCHW; the
 * output row is L2-normalized, so two embeddings compare by their dot product. Each model resizes for itself, since the two fits
 * share no intermediate, and the conversion to RGB runs on the small fitted image, never on the photo. A resize is `INTER_AREA`
 * for the downscale an import is, which averages the pixels it drops where bicubic aliases a large photo, and `INTER_CUBIC` where
 * an image smaller than the input on a side is enlarged, which `INTER_AREA` does not interpolate. The export script checks the
 * models on the same fits, and the Settings' similarity thresholds are calibrated to them: a change here means calibrating again.
 *
 * The two sessions hold about 1.2 GB resident, and on one thread a pass takes about 370 ms for DINOv2 and 375 ms for SigLIP 2,
 * plus about 50 ms a model to fit a 24-megapixel image; the figures and what they mean for the import are in
 * [[ImportParallelism]].
 */
class OnnxSceneEmbedder(models: ModelFiles) extends SceneEmbedder:
  import OnnxSceneEmbedder.*

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val environment: OrtEnvironment = OrtEnvironment.getEnvironment

  private val sessions: Map[SceneModel, OrtSession] = openSessions()

  private val inputs: Map[SceneModel, ModelInput] = SceneModel.values.map(model => model -> ModelInput(model)).toMap

  private val closed = AtomicBoolean(false)

  /** A session per Scene model, opened in order; when a later one fails to open, the ones open already are closed first */
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

  /**
   * The embeddings of an image as `ImageUtil.matFromBytes` decodes it (gray, BGR or BGRA), upright. A model that cannot prepare
   * the image or whose pass fails gives no embedding, logged at WARN with `name` (the file's), and the other model's stands; an
   * image no model can prepare (a channel count no decode produces) gives neither.
   */
  override def embed(image: Mat, name: String): SceneEmbeddings =
    SceneEmbeddings(dinov2 = embed(image, SceneModel.Dinov2, name), siglip2 = embed(image, SceneModel.Siglip2, name))

  private def embed(image: Mat, model: SceneModel, name: String): Option[SceneEmbedding] =
    try Some(run(prepare(image, model), model))
    catch
      case NonFatal(e) =>
        logger.warn(s"Scene model ${model.name} could not embed $name", e)
        None

  /** One pass of a model over the prepared pixels: the normalized NCHW tensor in, the L2-normalized output row out */
  private def run(rgb: Array[Byte], model: SceneModel): SceneEmbedding =
    val input = inputs(model)
    val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalize(rgb, input)), input.shape)
    try OnnxSessions.embed(sessions(model), INPUT, tensor, model)
    finally tensor.close()

  /**
   * The packed RGB bytes as the model's NCHW input: one plane per channel, each pixel scaled and shifted by the channel's
   * constants
   */
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

  /** Closes the sessions, once (ONNX Runtime warns on a second close of a session); the environment is the process's and stays */
  override def close(): Unit = if closed.compareAndSet(false, true) then sessions.values.foreach(_.close())
