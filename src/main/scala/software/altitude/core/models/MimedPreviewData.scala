package software.altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object MimedPreviewData {
  final val MIME_TYPE = "image/png"
  final val FILE_EXTENSION = "png"

  implicit def fromJson(json: JsValue): MimedPreviewData = {
    val data: String = (json \ C.MimedData.DATA).as[String]

    MimedPreviewData(
      assetId = (json \ C.MimedData.ASSET_ID).as[String],
      data = Base64.decodeBase64(data)
    )
  }
}

case class MimedPreviewData(assetId: String,
                            data: Array[Byte]) extends BaseModel with NoId {

  val mimeType: String = MimedPreviewData.MIME_TYPE

  override def toJson: JsObject = {
    Json.obj(
      C.MimedData.ASSET_ID -> assetId,
      C.MimedData.MIME_TYPE -> mimeType,
      C.MimedData.DATA -> Base64.encodeBase64String(data)
    )
  }
}
