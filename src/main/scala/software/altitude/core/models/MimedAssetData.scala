package software.altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json

import scala.language.implicitConversions

object MimedAssetData {
  implicit def fromJson(json: JsValue): MimedAssetData = {
    val data: String = (json \ Field.MimedData.DATA).as[String]

    MimedAssetData(
      assetId = (json \ Field.MimedData.ASSET_ID).as[String],
      mimeType = (json \ Field.MimedData.MIME_TYPE).as[String],
      data = Base64.decodeBase64(data)
    )
  }
}

case class MimedAssetData(assetId: String,
                          mimeType: String,
                          data: Array[Byte]) extends BaseModel with NoId {

  override def toJson: JsObject = {
    Json.obj(
      Field.MimedData.ASSET_ID -> assetId,
      Field.MimedData.MIME_TYPE -> mimeType,
      Field.MimedData.DATA -> Base64.encodeBase64String(data)
    )
  }
}
