package altitude.core.models
import altitude.core.json.UpickleConverters._
import altitude.core.util.ImageUtil.matFromBytes
import java.time.LocalDateTime
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import ujson.Value
import upickle.default.ReadWriter
import upickle.default.ReadWriter.join
import upickle.default.write
import upickle.default.writeJs

object Face {
  // For sorting faces by detection score automatically, highest score first
  implicit val faceOrdering: Ordering[Face] = Ordering.by(-_.detectionScore)
}

case class Face(
    id: Option[String] = None,
    x1: Int,
    y1: Int,
    width: Int,
    height: Int,
    assetId: Option[String] = None,
    personId: Option[String] = None,
    personLabel: Option[Int] = None,
    detectionScore: Double,
    checksum: Int,
    embeddings: Array[Float],
    features: Array[Float],
    alignedImageGs: Array[Byte] = Array.emptyByteArray)
  extends BaseModel
  derives ReadWriter:

  def toJsonString: String = write(this)

  def toJson: Value = writeJs(this)

  override val createdAt: Option[LocalDateTime] = None
  override val updatedAt: Option[LocalDateTime] = None

  val alignedImageGsMat: Mat = if (alignedImageGs.length > 0) matFromBytes(alignedImageGs) else new Mat()

  val featuresMat: Mat = {
    val floatMat = new MatOfFloat()
    floatMat.fromArray(features: _*)
    floatMat
  }

  override def toString: String =
    s"FACE $id. Label: $personLabel. Score: $detectionScore, ${width}x$height at ($x1, $y1)"

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match {
    case that: Face if !that.canEqual(this) => false
    case that: Face => that.id == this.id
    case _ => false
  }

  override def hashCode: Int = super.hashCode
