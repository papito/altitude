package altitude.core.models

import play.api.libs.json.*
import play.api.libs.json.JsonNaming.SnakeCase

object Stat:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[Stat] = Json.format[Stat]
  given Conversion[JsValue, Stat] = json => Json.fromJson[Stat](json).get
  given Conversion[Stat, JsObject] = stats => stats.toJson

case class Stat(dimension: String, dimVal: Int):
  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
