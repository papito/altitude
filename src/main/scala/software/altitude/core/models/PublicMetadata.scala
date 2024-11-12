package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions

object PublicMetadata {
  implicit def fromJson(json: JsValue): PublicMetadata = {

    PublicMetadata(
      deviceModel = (json \ Field.PublicMetadata.DEVICE_MODEL).asOpt[String],
      fNumber = (json \ Field.PublicMetadata.F_NUMBER).asOpt[String],
      focalLength = (json \ Field.PublicMetadata.FOCAL_LENGTH).asOpt[String],
      iso = (json \ Field.PublicMetadata.ISO).asOpt[String],
      exposureTime = (json \ Field.PublicMetadata.EXPOSURE_TIME).asOpt[String],
      dateTimeOriginal = (json \ Field.PublicMetadata.DATE_TIME_ORIGINAL).asOpt[String]
    )
  }
}

case class PublicMetadata(deviceModel: Option[String] = None,
                          fNumber: Option[String] = None,
                          focalLength: Option[String] = None,
                          iso: Option[String] = None,
                          exposureTime: Option[String] = None,
                          dateTimeOriginal: Option[String] = None) extends BaseModel with NoId {

  override def toJson: JsObject = {
    Json.obj(
      Field.PublicMetadata.DEVICE_MODEL -> deviceModel,
      Field.PublicMetadata.F_NUMBER -> fNumber,
      Field.PublicMetadata.FOCAL_LENGTH -> focalLength,
      Field.PublicMetadata.ISO -> iso,
      Field.PublicMetadata.EXPOSURE_TIME -> exposureTime,
      Field.PublicMetadata.DATE_TIME_ORIGINAL -> dateTimeOriginal
    )
  }
}
