package software.altitude.core.models

import java.time.LocalDateTime
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.language.implicitConversions

import software.altitude.core.util.ImageUtil.matFromBytes

object Face {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Face] = Json.format[Face]
  implicit def fromJson(json: JsValue): Face = Json.fromJson[Face](json).get

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
  extends BaseModel {

  override val createdAt: Option[LocalDateTime] = None
  override val updatedAt: Option[LocalDateTime] = None

  val alignedImageGsMat: Mat = if (alignedImageGs.length > 0) matFromBytes(alignedImageGs) else new Mat()

  val featuresMat: Mat = {
    val floatMat = new MatOfFloat()
    floatMat.fromArray(features: _*)
    floatMat
  }

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def toString: String =
    s"FACE $id. Label: $personLabel. Score: $detectionScore, ${width}x$height at ($x1, $y1)"

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match {
    case that: Face if !that.canEqual(this) => false
    case that: Face => that.id == this.id
    case _ => false
  }

  override def hashCode: Int = super.hashCode
}
