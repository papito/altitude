package altitude.core.models

import org.apache.commons.codec.binary.Base64

import altitude.core.FieldConst
import altitude.core.util.JsonCodec

object MimedAssetData:
  given JsonCodec.ReadWriter[MimedAssetData] = JsonCodec
    .readwriter[ujson.Value]
    .bimap(
      (ma: MimedAssetData) =>
        ujson.Obj(
          FieldConst.MimedData.ASSET_ID -> ujson.Str(ma.assetId),
          FieldConst.MimedData.MIME_TYPE -> ujson.Str(ma.mimeType),
          FieldConst.MimedData.DATA -> ujson.Str(Base64.encodeBase64String(ma.data))
        ),
      (json: ujson.Value) =>
        MimedAssetData(
          assetId = json(FieldConst.MimedData.ASSET_ID).str,
          mimeType = json(FieldConst.MimedData.MIME_TYPE).str,
          data = Base64.decodeBase64(json(FieldConst.MimedData.DATA).str)
        )
    )

  given Conversion[ujson.Value, MimedAssetData] = json => JsonCodec.read[MimedAssetData](json)

case class MimedAssetData(assetId: String, mimeType: String, data: Array[Byte]) extends BaseModel with NoId with NoDates:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
