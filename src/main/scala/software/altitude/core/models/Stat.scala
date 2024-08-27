package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions


object Stat {
  implicit def fromJson(json: JsValue): Stat = Stat(
      (json \ Field.Stat.DIMENSION).as[String],
      (json \ Field.Stat.DIM_VAL).as[Int]
  )

  implicit def toJson(stats: Stat): JsObject = stats.toJson
}

case class Stat(dimension: String, dimVal: Int) {

  def toJson: JsObject = {
    Json.obj(
      Field.Stat.DIMENSION -> dimension,
      Field.Stat.DIM_VAL -> dimVal)
  }
}
