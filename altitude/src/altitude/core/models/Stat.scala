package altitude.core.models

import altitude.core.util.JsonCodec
import JsonCodec.given
import JsonCodec.macroRW

object Stat:
  given JsonCodec.ReadWriter[Stat] = JsonCodec.macroRW
  given Conversion[ujson.Value, Stat] = json => JsonCodec.read[Stat](json)
  given Conversion[Stat, ujson.Obj] = stat => stat.toJson

case class Stat(dimension: String, dimVal: Int):
  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
