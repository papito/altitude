package software.altitude.core.models

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.language.implicitConversions

object Stat {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Stat] = Json.format[Stat]
  implicit def fromJson(json: JsValue): Stat = Json.fromJson[Stat](json).get
  implicit def toJson(stats: Stat): JsObject = stats.toJson
}

case class Stat(dimension: String, dimVal: Int) {
  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
}
