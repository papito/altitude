package software.altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.{JsObject, JsValue, Json}
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object MimedFaceData {
  final val MIME_TYPE = "image/png"
  final val FILE_EXTENSION = "png"

  implicit def fromJson(json: JsValue): MimedFaceData = {
    val data: String = (json \ C.MimedData.DATA).as[String]

    MimedFaceData(
      data = Base64.decodeBase64(data)
    )
  }
}

case class MimedFaceData(data: Array[Byte]) extends BaseModel with NoId {

  val mimeType: String = MimedFaceData.MIME_TYPE

  override def toJson: JsObject = {
    Json.obj(
      C.MimedData.MIME_TYPE -> mimeType,
      C.MimedData.DATA -> Base64.encodeBase64String(data)
    )
  }
}
