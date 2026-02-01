package altitude.core.models

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.OWrites
import play.api.libs.json.Reads

import altitude.core.FieldConst

object MimedAssetData:
  given reads: Reads[MimedAssetData] = (json: JsValue) =>
    val data: String = (json \ FieldConst.MimedData.DATA).as[String]
    JsSuccess(
      MimedAssetData(
        assetId = (json \ FieldConst.MimedData.ASSET_ID).as[String],
        mimeType = (json \ FieldConst.MimedData.MIME_TYPE).as[String],
        data = Base64.decodeBase64(data)
      ))

  given writes: OWrites[MimedAssetData] = (mimedAssetData: MimedAssetData) =>
    Json.obj(
      FieldConst.MimedData.ASSET_ID -> mimedAssetData.assetId,
      FieldConst.MimedData.MIME_TYPE -> mimedAssetData.mimeType,
      FieldConst.MimedData.DATA -> Base64.encodeBase64String(mimedAssetData.data)
    )

  given Conversion[JsValue, MimedAssetData] = json => Json.fromJson[MimedAssetData](json).get

case class MimedAssetData(assetId: String, mimeType: String, data: Array[Byte]) extends BaseModel with NoId with NoDates:

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
