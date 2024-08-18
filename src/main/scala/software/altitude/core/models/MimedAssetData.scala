package software.altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object MimedAssetData {
  implicit def fromJson(json: JsValue): MimedAssetData = {
    val data: String = (json \ C.MimedData.DATA).as[String]

    MimedAssetData(
      assetId = (json \ C.MimedData.ASSET_ID).as[String],
      mimeType = (json \ C.MimedData.MIME_TYPE).as[String],
      data = Base64.decodeBase64(data)
    )
  }
}

case class MimedAssetData(assetId: String,
                          mimeType: String,
                          data: Array[Byte]) extends BaseModel with NoId {

  override def toJson: JsObject = {
    Json.obj(
      C.MimedData.ASSET_ID -> assetId,
      C.MimedData.MIME_TYPE -> mimeType,
      C.MimedData.DATA -> Base64.encodeBase64String(data)
    )
  }
}
