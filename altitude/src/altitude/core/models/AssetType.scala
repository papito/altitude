package altitude.core.models

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

object AssetType:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[AssetType] = Json.format[AssetType]
  given Conversion[JsValue, AssetType] = json => Json.fromJson[AssetType](json).get

case class AssetType(mediaType: String, mediaSubtype: String, mime: String) extends BaseModel with NoId with NoDates:

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def equals(other: Any): Boolean = other match {
    case that: AssetType =>
      that.mime == this.mime &&
      that.mediaType == this.mediaType &&
      that.mediaSubtype == this.mediaSubtype
    case _ => false
  }

  override def toString: String = List(mediaType, mediaSubtype, mime).mkString(":")

  override def hashCode: Int = (mediaType + mediaSubtype + mime).hashCode
