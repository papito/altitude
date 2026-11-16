package altitude.core.models

import java.util.Arrays

import altitude.core.FieldConst
import altitude.core.util.JsonCodec
import altitude.core.util.ModelFile

/** How `OnnxSceneEmbedder` fits an image of any shape to the square a Scene model takes */
enum SceneFit:
  /** The short side scaled to the square, then the center cut out: the ends of the long side are lost */
  case CenterCrop

  /** The whole image scaled to the square, aspect not kept: nothing is lost, a wide frame is narrowed */
  case Squash

/**
 * A Scene model: a vision model the import embeds every still image with. `mean` and `std` are per channel in RGB order, applied
 * after a scale to 0..1, and are what the checkpoint was trained with.
 */
enum SceneModel(
    val name: String,
    val dimensions: Int,
    val column: String,
    val file: ModelFile,
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
      ModelFile.Dinov2,
      224,
      SceneFit.CenterCrop,
      IArray(0.485f, 0.456f, 0.406f),
      IArray(0.229f, 0.224f, 0.225f)
    )

  /**
   * The SigLIP 2 ViT-B/16 image tower at 256 pixels, `google/siglip2-base-patch16-256`: the attention-pooled output over the
   * whole frame, squashed as the checkpoint's processor does so a wide frame's edges stay in view, normalized to -1..1
   */
  case Siglip2
    extends SceneModel(
      "siglip2",
      768,
      FieldConst.Asset.SIGLIP2_EMBEDDING,
      ModelFile.Siglip2Image,
      256,
      SceneFit.Squash,
      IArray(0.5f, 0.5f, 0.5f),
      IArray(0.5f, 0.5f, 0.5f)
    )

object SceneModel:

  /**
   * SigLIP 2's learned scale `t = exp(logit_scale)` and bias `b = logit_bias`: an image and a description match with probability
   * `sigmoid(cosine * t + b)`. They are the checkpoint's, and `export-models.py` in `altitude-models` refuses a text tower whose
   * checkpoint holds others. A probability of 0.5 is the cosine 0.1486, and a hundredth of cosine is a factor of about 3 in the
   * odds.
   */
  val SIGLIP2_LOGIT_SCALE = 112.90117645263672
  val SIGLIP2_LOGIT_BIAS = -16.77180290222168

  /** The cosine at which SigLIP 2 gives an image and a description the `probability` of matching, `(ln(p / (1 - p)) - b) / t` */
  def siglip2Cosine(probability: Double): Double =
    require(probability > 0 && probability < 1, s"A probability is between 0 and 1, not $probability")
    (math.log(probability / (1 - probability)) - SIGLIP2_LOGIT_BIAS) / SIGLIP2_LOGIT_SCALE

object SceneEmbedding:
  // The wire form is the bare float array; no API returns it today, but an Asset carries it in JSON
  given JsonCodec.ReadWriter[SceneEmbedding] =
    JsonCodec.readwriter[Array[Float]].bimap(_.array, values => SceneEmbedding(IArray.unsafeFromArray(values)))

  private val SHOWN_VALUES = 3

/**
 * An L2-normalized vector a vision model computes over a whole still image. Embeddings of different models never compare, and
 * since their dimensions can be equal (768 for both today), [[SceneEmbeddings]] keeps them apart, a field per model. Equality is
 * by value: a case class over an array would compare it by reference, so two reads of one row would differ.
 *
 * A description's embedding by SigLIP 2's text tower is one too, in the space of that model's image embeddings.
 */
case class SceneEmbedding(values: IArray[Float]):

  // An IArray is the float[] itself at runtime, which the JDK's array methods take
  private def array: Array[Float] = values.asInstanceOf[Array[Float]]

  override def equals(other: Any): Boolean = other match
    case that: SceneEmbedding => Arrays.equals(array, that.array)
    case _ => false

  override def hashCode: Int = Arrays.hashCode(array)

  override def toString: String =
    s"SceneEmbedding(${values.length}: ${values.take(SceneEmbedding.SHOWN_VALUES).mkString(", ")}, ...)"

  /**
   * Cosine similarity, the dot product of two unit vectors. Different lengths are refused; two models of one length are the
   * caller's to keep apart.
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

  def of(model: SceneModel): Option[SceneEmbedding] = model match
    case SceneModel.Dinov2 => dinov2
    case SceneModel.Siglip2 => siglip2
