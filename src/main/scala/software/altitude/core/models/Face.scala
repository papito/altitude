package software.altitude.core.models

import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import play.api.libs.json._
import software.altitude.core.util.ImageUtil.matFromBytes

import scala.language.implicitConversions

object Face {
  implicit def fromJson(json: JsValue): Face = {

    Face(
      id = (json \ Field.ID).asOpt[String],
      x1 = (json \ Field.Face.X1).as[Int],
      y1 = (json \ Field.Face.Y1).as[Int],
      width = (json \ Field.Face.WIDTH).as[Int],
      height = (json \ Field.Face.HEIGHT).as[Int],
      assetId = (json \ Field.Face.ASSET_ID).asOpt[String],
      personId = (json \ Field.Face.PERSON_ID).asOpt[String],
      personLabel = (json \ Field.Face.PERSON_LABEL).asOpt[Int],
      detectionScore = (json \ Field.Face.DETECTION_SCORE).as[Double],
      embeddings = (json \ Field.Face.EMBEDDINGS).as[Array[Float]],
      features = (json \ Field.Face.FEATURES).as[Array[Float]],
      image = (json \ Field.Face.IMAGE).as[Array[Byte]],
      displayImage = (json \ Field.Face.DISPLAY_IMAGE).as[Array[Byte]],
      alignedImage = (json \ Field.Face.ALIGNED_IMAGE).as[Array[Byte]],
      alignedImageGs = (json \ Field.Face.ALIGNED_IMAGE_GS).as[Array[Byte]]
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
      Field.Face.X1 -> x1,
      Field.Face.Y1 -> y1,
      Field.Face.WIDTH -> width,
      Field.Face.HEIGHT -> height,
      Field.Face.ASSET_ID -> assetId,
      Field.Face.PERSON_ID -> personId,
      Field.Face.PERSON_LABEL -> personLabel,
      Field.Face.DETECTION_SCORE -> detectionScore,
      Field.Face.EMBEDDINGS -> embeddings,
      Field.Face.FEATURES -> features,
      Field.Face.IMAGE -> image,
      Field.Face.DISPLAY_IMAGE -> displayImage,
      Field.Face.ALIGNED_IMAGE -> alignedImage,
      Field.Face.ALIGNED_IMAGE_GS -> alignedImageGs
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
