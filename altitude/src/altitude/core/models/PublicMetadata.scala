package altitude.core.models

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

object PublicMetadata:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[PublicMetadata] = Json.format[PublicMetadata]
  given Conversion[JsValue, PublicMetadata] = json => Json.fromJson[PublicMetadata](json).get

case class PublicMetadata(
    deviceModel: Option[String] = None,
    fNumber: Option[String] = None,
    focalLength: Option[String] = None,
    iso: Option[String] = None,
    exposureTime: Option[String] = None,
    dateTimeOriginal: Option[String] = None)
  extends BaseModel
  with NoId
  with NoDates:

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
