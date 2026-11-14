package altitude.core.models

import java.util.Arrays

import altitude.core.FieldConst
import altitude.core.util.JsonCodec

/** How `OnnxSceneEmbedder` fits an image, of any size and shape, to the square a Scene model takes */
enum SceneFit:
  /** The short side scaled to the square's side, then the center square: the ends of the long side are left out */
  case CenterCrop

  /** The whole image scaled to the square, its aspect not kept: nothing is left out, and a wide frame is narrowed */
  case Squash

/**
 * One of the vision models the import embeds every still image with (the glossary's Scene model). Each knows its name in the logs
 * (`name`), how its embedding is stored (`column`: its `asset` column, named in `FieldConst.Asset`, `dimensions`), where its ONNX
 * export lives on the classpath, the side of the square it takes (`inputSize`) and how an image is fitted to it (`fit`), and the
 * per-channel mean and std, in RGB order, its input is normalized with after a scale to 0..1: what the checkpoint was trained
 * with, so `OnnxSceneEmbedder` feeds it what it expects.
 */
enum SceneModel(
    val name: String,
    val dimensions: Int,
    val column: String,
    val resourcePath: String,
    val inputSize: Int,
    val fit: SceneFit,
    val mean: IArray[Float],
    val std: IArray[Float]):

  /** DINOv2 ViT-B/14, `facebook/dinov2-base`: the pooled CLS token of a center square, ImageNet normalization */
  case Dinov2
    extends SceneModel(
      "dinov2",
      768,
      FieldConst.Asset.DINOV2_EMBEDDING,
      "/onnx/dinov2_vitb14.onnx",
      224,
      SceneFit.CenterCrop,
      IArray(0.485f, 0.456f, 0.406f),
      IArray(0.229f, 0.224f, 0.225f)
    )

  /**
   * The SigLIP 2 ViT-B/16 image tower at 256 pixels, `google/siglip2-base-patch16-256`: the vision tower's pooled output (its
   * attention-pooling head) over the whole frame, squashed as the checkpoint's processor squashes it so that the edges of a wide
   * frame stay in view, normalized to -1..1
   */
  case Siglip2
    extends SceneModel(
      "siglip2",
      768,
      FieldConst.Asset.SIGLIP2_EMBEDDING,
      "/onnx/siglip2_b16_256_image.onnx",
      256,
      SceneFit.Squash,
      IArray(0.5f, 0.5f, 0.5f),
      IArray(0.5f, 0.5f, 0.5f)
    )

object SceneModel:

  /**
   * What SigLIP 2's checkpoint learned beside its two towers: the scale `t = exp(logit_scale)` and the bias `b = logit_bias` of
   * the probability that an image and a description match, `sigmoid(cosine * t + b)` over the cosine of the image's Scene
   * embedding and the description's embedding (`DescribeEmbedder`). They are the checkpoint's, as the mean and std above are, and
   * `scripts/fetch-models.py` refuses to export a text tower whose checkpoint holds others. A probability `p` is the cosine
   * `(ln(p / (1 - p)) - b) / t`: 0.5 is the cosine 0.1486, and a hundredth of cosine is a factor of about 3 in the odds.
   */
  val SIGLIP2_LOGIT_SCALE = 112.90117645263672
  val SIGLIP2_LOGIT_BIAS = -16.77180290222168

object SceneEmbedding:
  // The wire form is the float array itself; no API answers it today, but an Asset carries it in JSON
  given JsonCodec.ReadWriter[SceneEmbedding] =
    JsonCodec.readwriter[Array[Float]].bimap(_.array, values => SceneEmbedding(IArray.unsafeFromArray(values)))

  /** How many of the values `toString` shows */
  private val SHOWN_VALUES = 3

/**
 * A vector a vision model computes over a whole still image, describing its content and layout (the glossary's Scene embedding):
 * L2-normalized, as `OnnxSceneEmbedder` makes it. Two embeddings of one model compare by [[similarity]], 1 for the same picture;
 * embeddings of different models never compare, and since the models' dimensions can be equal (both are 768 today) it is
 * [[SceneEmbeddings]] that keeps them apart, a field per model. The values are immutable, and two embeddings are equal when their
 * values are: a case class over an array would compare it by reference, so two reads of one row would differ.
 *
 * A description's embedding by SigLIP 2's text tower (`DescribeEmbedder`) is one of these too: a vector in the space of that
 * model's Scene embeddings, which compares with an image's by the same [[similarity]].
 */
case class SceneEmbedding(values: IArray[Float]):

  // An IArray is the float[] itself at runtime, which the JDK's array methods take
  private def array: Array[Float] = values.asInstanceOf[Array[Float]]

  override def equals(other: Any): Boolean = other match
    case that: SceneEmbedding => Arrays.equals(array, that.array)
    case _ => false

  override def hashCode: Int = Arrays.hashCode(array)

  /** The dimensions and the first few values, never the whole vector */
  override def toString: String =
    s"SceneEmbedding(${values.length}: ${values.take(SceneEmbedding.SHOWN_VALUES).mkString(", ")}, ...)"

  /**
   * The cosine similarity to another embedding of the same model: the dot product of two unit vectors, -1 to 1. Two lengths that
   * differ are refused; two models of one length are the caller's to keep apart (`SceneEmbeddings.of`).
   */
  def similarity(other: SceneEmbedding): Double =
    if values.length != other.values.length then
      throw IllegalArgumentException(
        s"Embeddings of ${values.length} and ${other.values.length} dimensions are of different models and never compare")
    var dot = 0.0
    var i = 0
    while i < values.length do
      dot += values(i).toDouble * other.values(i)
      i += 1
    dot

object SceneEmbeddings:
  given JsonCodec.ReadWriter[SceneEmbeddings] = JsonCodec.macroRW

/** A frame's Scene embeddings, one per Scene model, each absent where the model did not embed it (a Video, a failed pass) */
case class SceneEmbeddings(dinov2: Option[SceneEmbedding] = None, siglip2: Option[SceneEmbedding] = None):

  /** The embedding of one Scene model */
  def of(model: SceneModel): Option[SceneEmbedding] = model match
    case SceneModel.Dinov2 => dinov2
    case SceneModel.Siglip2 => siglip2
