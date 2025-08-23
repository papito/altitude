package altitude.core.models

import ujson.Value
import upickle.default.ReadWriter
import upickle.default.write
import upickle.default.writeJs

case class PublicMetadata(
    deviceModel: Option[String] = None,
    fNumber: Option[String] = None,
    focalLength: Option[String] = None,
    iso: Option[String] = None,
    exposureTime: Option[String] = None,
    dateTimeOriginal: Option[String] = None)
  extends BaseModel
  with NoId
  with NoDates
  derives ReadWriter:

  def toJsonString: String = write(this)
  def toJson: Value = writeJs(this)
