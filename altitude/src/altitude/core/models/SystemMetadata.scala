package altitude.core.models

import altitude.core.util.JsonCodec
import JsonCodec.given
import JsonCodec.macroRW

import scala.language.implicitConversions

object SystemMetadata:
  given JsonCodec.ReadWriter[SystemMetadata] = JsonCodec.macroRW
  given Conversion[ujson.Value, SystemMetadata] = json => JsonCodec.read[SystemMetadata](json)

case class SystemMetadata(version: Int, isInitialized: Boolean)
  extends BaseModel
  with NoId
  with NoDates:
  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def toString: String = s"<system> version=$version, initialized=$isInitialized"
