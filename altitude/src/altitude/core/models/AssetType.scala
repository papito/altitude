package altitude.core.models

import ujson.Value
import upickle.default.ReadWriter
import upickle.default.write
import upickle.default.writeJs

case class AssetType(mediaType: String, mediaSubtype: String, mime: String) extends BaseModel with NoId with NoDates
  derives ReadWriter:
  def toJsonString: String = write(this)

  def toJson: Value = writeJs(this)

  override def equals(other: Any): Boolean = other match {
    case that: AssetType =>
      that.mime == this.mime &&
      that.mediaType == this.mediaType &&
      that.mediaSubtype == this.mediaSubtype
    case _ => false
  }

  override def toString: String = List(mediaType, mediaSubtype, mime).mkString(":")

  override def hashCode: Int = (mediaType + mediaSubtype + mime).hashCode
