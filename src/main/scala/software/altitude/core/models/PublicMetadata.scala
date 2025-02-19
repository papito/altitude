package software.altitude.core.models

import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._

import scala.language.implicitConversions

object PublicMetadata {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[PublicMetadata] = Json.format[PublicMetadata]
  implicit def fromJson(json: JsValue): PublicMetadata = Json.fromJson[PublicMetadata](json).get
}

case class PublicMetadata(
    deviceModel: Option[String] = None,
    fNumber: Option[String] = None,
    focalLength: Option[String] = None,
    iso: Option[String] = None,
    exposureTime: Option[String] = None,
    dateTimeOriginal: Option[String] = None)
  extends BaseModel
  with NoId
  with NoDates {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
}
