package software.altitude.core.models

import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import play.api.libs.json._
import software.altitude.core.util.ImageUtil.matFromBytes
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object Face {
  implicit def fromJson(json: JsValue): Face = {

    Face(
      id = (json \ C.Base.ID).asOpt[String],
      x1 = (json \ C.Face.X1).as[Int],
      y1 = (json \ C.Face.Y1).as[Int],
      width = (json \ C.Face.WIDTH).as[Int],
      height = (json \ C.Face.HEIGHT).as[Int],
      assetId = (json \ C.Face.ASSET_ID).asOpt[String],
      personId = (json \ C.Face.PERSON_ID).asOpt[String],
      personLabel = (json \ C.Face.PERSON_LABEL).asOpt[Int],
      detectionScore = (json \ C.Face.DETECTION_SCORE).as[Double],
      embeddings = (json \ C.Face.EMBEDDINGS).as[Array[Float]],
      features = (json \ C.Face.FEATURES).as[Array[Float]],
      image = (json \ C.Face.IMAGE).as[Array[Byte]],
      displayImage = (json \ C.Face.DISPLAY_IMAGE).as[Array[Byte]],
      alignedImage = (json \ C.Face.ALIGNED_IMAGE).as[Array[Byte]],
      alignedImageGs = (json \ C.Face.ALIGNED_IMAGE_GS).as[Array[Byte]]
    ).withCoreAttr(json)
  }

  // For sorting faces by detection score automatically, highest score first
  implicit val faceOrdering: Ordering[Face] = Ordering.by(-_.detectionScore)
}

case class Face(id: Option[String] = None,
                x1: Int,
                y1: Int,
                width: Int,
                height: Int,
                assetId: Option[String] = None,
                personId: Option[String] = None,
                personLabel: Option[Int] = None,
                detectionScore: Double,
                embeddings: Array[Float],
                features: Array[Float],
                image: Array[Byte],
                displayImage: Array[Byte],
                alignedImage: Array[Byte],
                alignedImageGs: Array[Byte]) extends BaseModel {

  val alignedImageGsMat: Mat = if (alignedImageGs.length > 0) matFromBytes(alignedImageGs) else new Mat()

  val featuresMat: Mat = {
    val floatMat = new MatOfFloat()
    floatMat.fromArray(features: _*)
    floatMat
  }

  override def toJson: JsObject = {
    Json.obj(
      C.Face.X1 -> x1,
      C.Face.Y1 -> y1,
      C.Face.WIDTH -> width,
      C.Face.HEIGHT -> height,
      C.Face.ASSET_ID -> assetId,
      C.Face.PERSON_ID -> personId,
      C.Face.PERSON_LABEL -> personLabel,
      C.Face.DETECTION_SCORE -> detectionScore,
      C.Face.EMBEDDINGS -> embeddings,
      C.Face.FEATURES -> features,
      C.Face.IMAGE -> image,
      C.Face.DISPLAY_IMAGE -> displayImage,
      C.Face.ALIGNED_IMAGE -> alignedImage,
      C.Face.ALIGNED_IMAGE_GS -> alignedImageGs
    ) ++ coreJsonAttrs
  }

  override def toString: String =
    s"FACE $id. Label: ${personLabel}. Score: $detectionScore, ${width}x${height} at ($x1, $y1)"

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match {
    case that: Face if !that.canEqual( this) => false
    case that: Face => that.id == this.id
    case _ => false
  }

  override def hashCode: Int = super.hashCode
}
