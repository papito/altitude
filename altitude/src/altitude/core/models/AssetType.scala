package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AssetType:
  given JsonCodec.ReadWriter[AssetType] = JsonCodec.macroRW
  given Conversion[ujson.Value, AssetType] = json => JsonCodec.read[AssetType](json)

case class AssetType(mediaType: String, mediaSubtype: String, mime: String) extends BaseModel with NoId with NoDates:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def equals(other: Any): Boolean = other match
    case that: AssetType =>
      that.mime == this.mime &&
      that.mediaType == this.mediaType &&
      that.mediaSubtype == this.mediaSubtype
    case _ => false

  override def toString: String = List(mediaType, mediaSubtype, mime).mkString(":")

  override def hashCode: Int = (mediaType + mediaSubtype + mime).hashCode
