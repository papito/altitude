package software.altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsObject
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.OWrites
import play.api.libs.json.Reads
import software.altitude.core.FieldConst

import scala.language.implicitConversions

object MimedFaceData {
  final val MIME_TYPE = "image/png"
  final val FILE_EXTENSION = "png"

  implicit val reads: Reads[MimedFaceData] = (json: JsValue) => {
    val data: String = (json \ FieldConst.MimedData.DATA).as[String]
    JsSuccess(MimedFaceData(
      data = Base64.decodeBase64(data)
    ))
  }

  implicit val writes: OWrites[MimedFaceData] = (mimedFaceData: MimedFaceData) => {
    Json.obj(
      FieldConst.MimedData.MIME_TYPE -> mimedFaceData.mimeType,
      FieldConst.MimedData.DATA -> Base64.encodeBase64String(mimedFaceData.data)
    )
  }

  implicit def fromJson(json: JsValue): MimedFaceData = Json.fromJson[MimedFaceData](json).get
}

case class MimedFaceData(data: Array[Byte]) extends BaseModel with NoId with NoDates {
  val mimeType: String = MimedFaceData.MIME_TYPE

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
}
