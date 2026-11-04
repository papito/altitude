package altitude.core.models

import java.util.Arrays

import altitude.core.FieldConst
import altitude.core.util.JsonCodec

/**
 * One of the vision models the import embeds every still image with (the glossary's Scene model). Each knows how it is stored
 * (`dbValue`, `column`: its `asset` column, named in `FieldConst.Asset`, `dimensions`), its name on the screen (`label`), where
 * its ONNX export lives on the classpath, and the per-channel mean and std, in RGB order, its input is normalized with after a
 * scale to 0..1: what the checkpoint was trained with, so `SceneEmbedder` feeds it what it expects.
 */
enum SceneModel(
    val dbValue: String,
    val label: String,
    val dimensions: Int,
    val column: String,
    val resourcePath: String,
    val mean: IArray[Float],
    val std: IArray[Float]):

  /** DINOv2 ViT-B/14, `facebook/dinov2-base`: the pooled CLS token, ImageNet normalization */
  case Dinov2
    extends SceneModel(
      "dinov2",
      "DINOv2",
      768,
      FieldConst.Asset.DINOV2_EMBEDDING,
      "/onnx/dinov2_vitb14.onnx",
      IArray(0.485f, 0.456f, 0.406f),
      IArray(0.229f, 0.224f, 0.225f))

  /** The CLIP ViT-B/16 image tower, `openai/clip-vit-base-patch16`: the projected image embedding, CLIP's normalization */
  case Clip
    extends SceneModel(
      "clip",
      "CLIP",
      512,
      FieldConst.Asset.CLIP_EMBEDDING,
      "/onnx/clip_vitb16_image.onnx",
      IArray(0.48145466f, 0.4578275f, 0.40821073f),
      IArray(0.26862954f, 0.26130258f, 0.27577711f)
    )

object SceneModel:
  def fromDbValue(value: String): Option[SceneModel] = values.find(_.dbValue == value)

object SceneEmbedding:
  // The wire form is the float array itself; no API answers it today, but an Asset carries it in JSON
  given JsonCodec.ReadWriter[SceneEmbedding] =
    JsonCodec.readwriter[Array[Float]].bimap(_.array, values => SceneEmbedding(IArray.unsafeFromArray(values)))

  /** How many of the values `toString` shows */
  private val SHOWN_VALUES = 3

/**
 * A vector a vision model computes over a whole still image, describing its content and layout (the glossary's Scene embedding):
 * L2-normalized, as `SceneEmbedder` makes it. Two embeddings of one model compare by [[similarity]], 1 for the same picture;
 * embeddings of different models never compare, and the dimensions tell them apart. The values are immutable, and two embeddings
 * are equal when their values are: a case class over an array would compare it by reference, so two reads of one row would
 * differ.
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

  /** The cosine similarity to another embedding of the same model: the dot product of two unit vectors, -1 to 1 */
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
case class SceneEmbeddings(dinov2: Option[SceneEmbedding] = None, clip: Option[SceneEmbedding] = None):

  /** The embedding of one Scene model */
  def of(model: SceneModel): Option[SceneEmbedding] = model match
    case SceneModel.Dinov2 => dinov2
    case SceneModel.Clip => clip
