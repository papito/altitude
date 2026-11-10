package altitude.core.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
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

import altitude.core.Environment
import altitude.core.ImageException
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
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

object OnnxSceneEmbedder:
  /** The side of the square every Scene model takes */
  val INPUT_SIZE: Int = 224

  private val CHANNELS = 3
  private val PIXELS = INPUT_SIZE * INPUT_SIZE
  private val INPUT_SHAPE: Array[Long] = Array(1L, CHANNELS, INPUT_SIZE, INPUT_SIZE)

  /**
   * A model's normalization folded into one multiply and add per channel: `(byte / 255 - mean) / std` is `byte * scale + offset`,
   * so the hot loop does no division
   */
  private class Normalization(model: SceneModel):
    val scale: IArray[Float] = model.std.map(std => 1f / (255f * std))
    val offset: IArray[Float] = model.mean.zip(model.std).map((mean, std) => -mean / std)

/**
 * The Scene embeddings of a still image, computed by every [[altitude.core.models.SceneModel]] through ONNX Runtime.
 *
 * The process has one `OrtEnvironment` and this class opens one `OrtSession` per Scene model when it is built, so a missing model
 * file fails the start and never an import (a library imported without embeddings would fall back to the hash silently). An
 * `OrtSession` is thread-safe, so every import thread runs the same two; each session is opened with one intra-op and one
 * inter-op thread, since the parallelism is the import threads' and twelve concurrent passes each spreading over every core would
 * thrash.
 *
 * The preprocessing is this project's own, not the checkpoints' reference transform (DINOv2's processor resizes the short side to
 * 256 bicubic and crops 224; CLIP's resizes the short side to 224 bicubic): the short side fitted to
 * [[OnnxSceneEmbedder.INPUT_SIZE]], a center crop of that square, converted to RGB, scaled to 0..1 and normalized per channel
 * with the model's mean and std, laid out NCHW; the output row is L2-normalized, so two embeddings compare by their dot product.
 * One pass prepares the one square for both models, since the resize and the crop are the costly part and the normalization is
 * each model's own; the fit is `INTER_AREA` for the downscale an import is, which averages the pixels it drops where bicubic
 * aliases a large photo, and `INTER_CUBIC` where an image smaller than the input is enlarged, which `INTER_AREA` does not
 * interpolate. The export script checks the models on the same transform, and the Settings' similarity thresholds are calibrated
 * to it: a change here means calibrating again.
 *
 * The two sessions hold about 1.1 GB resident, and on one thread a pass takes about 365 ms for DINOv2 and 270 ms for CLIP, plus
 * about 50 ms to fit a 24-megapixel image; the figures and what they mean for the import are in [[ImportParallelism]].
 */
class OnnxSceneEmbedder extends SceneEmbedder:
  import OnnxSceneEmbedder.*

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val environment: OrtEnvironment = OrtEnvironment.getEnvironment

  private val sessions: Map[SceneModel, OrtSession] = openSessions()

  private val normalizations: Map[SceneModel, Normalization] = SceneModel.values.map(model => model -> Normalization(model)).toMap

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
    val path = Environment.resolveResourcePath(model.resourcePath)
    val options = new OrtSession.SessionOptions()
    try
      options.setIntraOpNumThreads(1)
      options.setInterOpNumThreads(1)
      val session = environment.createSession(path, options)
      logger.info(s"Opened the ONNX session of Scene model ${model.name} from $path")
      session
    finally options.close()

  /**
   * The embeddings of an image as `ImageUtil.matFromBytes` decodes it (gray, BGR or BGRA), upright. A model whose pass fails
   * gives no embedding, logged at WARN with `name` (the file's), and the other model's stands; an image that cannot be prepared
   * at all gives neither.
   */
  override def embed(image: Mat, name: String): SceneEmbeddings =
    val pixels =
      try Some(prepare(image))
      catch
        case NonFatal(e) =>
          logger.warn(s"Could not prepare $name for the Scene models", e)
          None
    pixels match
      case None => SceneEmbeddings()
      case Some(rgb) => SceneEmbeddings(dinov2 = embed(rgb, SceneModel.Dinov2, name), clip = embed(rgb, SceneModel.Clip, name))

  private def embed(rgb: Array[Byte], model: SceneModel, name: String): Option[SceneEmbedding] =
    try Some(run(rgb, model))
    catch
      case NonFatal(e) =>
        logger.warn(s"Scene model ${model.name} could not embed $name", e)
        None

  /** The image's center square at the input size as packed RGB bytes, row-major: the models' common input before normalization */
  private def prepare(image: Mat): Array[Byte] =
    val fitted = fitShortSide(image)
    try
      val crop = centerCrop(fitted)
      try
        val rgb = toRgb(crop)
        try
          val bytes = new Array[Byte](PIXELS * CHANNELS)
          rgb.get(0, 0, bytes)
          bytes
        finally rgb.release()
      finally crop.release()
    finally fitted.release()

  /**
   * The image with its short side at the input size, the long side scaled by the same factor and never below it after rounding:
   * `INTER_AREA` when shrinking, `INTER_CUBIC` when enlarging an image under the input size
   */
  private def fitShortSide(image: Mat): Mat =
    val scale = INPUT_SIZE.toDouble / Math.min(image.width(), image.height())
    val width = Math.max(INPUT_SIZE, Math.round(image.width() * scale).toInt)
    val height = Math.max(INPUT_SIZE, Math.round(image.height() * scale).toInt)
    val interpolation = if scale <= 1.0 then Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
    val fitted = new Mat()
    try Imgproc.resize(image, fitted, new Size(width, height), 0, 0, interpolation)
    catch
      case e: Throwable =>
        fitted.release()
        throw e
    fitted

  /** The input-sized square in the middle of a fitted image: a view, released by the caller like any Mat */
  private def centerCrop(fitted: Mat): Mat =
    val x = (fitted.width() - INPUT_SIZE) / 2
    val y = (fitted.height() - INPUT_SIZE) / 2
    new Mat(fitted, new Rect(x, y, INPUT_SIZE, INPUT_SIZE))

  /** The crop as a continuous RGB Mat, whatever `ImageUtil` decoded the image as */
  private def toRgb(crop: Mat): Mat =
    val code = crop.channels() match
      case 1 => Imgproc.COLOR_GRAY2RGB
      case 3 => Imgproc.COLOR_BGR2RGB
      case 4 => Imgproc.COLOR_BGRA2RGB
      case channels => throw ImageException(s"An image of $channels channels cannot be embedded")
    val rgb = new Mat()
    try Imgproc.cvtColor(crop, rgb, code)
    catch
      case e: Throwable =>
        rgb.release()
        throw e
    rgb

  /** One pass of a model over the prepared pixels: the normalized NCHW tensor in, the L2-normalized output row out */
  private def run(rgb: Array[Byte], model: SceneModel): SceneEmbedding =
    val input = normalize(rgb, normalizations(model))
    val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), INPUT_SHAPE)
    try
      val result = sessions(model).run(Collections.singletonMap("image", tensor))
      try
        val output = result.get(0).asInstanceOf[OnnxTensor].getFloatBuffer
        if output.remaining() != model.dimensions then
          throw IllegalStateException(s"Scene model ${model.name} gave ${output.remaining()} values, not ${model.dimensions}")
        val values = new Array[Float](model.dimensions)
        output.get(values)
        SceneEmbedding(IArray.unsafeFromArray(unit(values)))
      finally result.close()
    finally tensor.close()

  /**
   * The packed RGB bytes as the model's NCHW input: one plane per channel, each pixel scaled and shifted by the channel's
   * constants
   */
  private def normalize(rgb: Array[Byte], normalization: Normalization): Array[Float] =
    val input = new Array[Float](CHANNELS * PIXELS)
    var channel = 0
    while channel < CHANNELS do
      val scale = normalization.scale(channel)
      val offset = normalization.offset(channel)
      val plane = channel * PIXELS
      var pixel = 0
      while pixel < PIXELS do
        input(plane + pixel) = (rgb(pixel * CHANNELS + channel) & 0xff) * scale + offset
        pixel += 1
      channel += 1
    input

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

  /** Closes the sessions, once (ONNX Runtime warns on a second close of a session); the environment is the process's and stays */
  override def close(): Unit = if closed.compareAndSet(false, true) then sessions.values.foreach(_.close())
