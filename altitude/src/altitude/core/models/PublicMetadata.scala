package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object PublicMetadata:
  given JsonCodec.ReadWriter[PublicMetadata] = JsonCodec.macroRW
  given Conversion[ujson.Value, PublicMetadata] = json => JsonCodec.read[PublicMetadata](json)

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

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
