package altitude.core.models

import org.apache.commons.codec.binary.Base64

import altitude.core.FieldConst
import altitude.core.util.JsonCodec

object MimedPreviewData:
  final val MIME_TYPE = "image/png"
  final val FILE_EXTENSION = "png"

  given JsonCodec.ReadWriter[MimedPreviewData] = JsonCodec
    .readwriter[ujson.Value]
    .bimap(
      (mp: MimedPreviewData) =>
        ujson.Obj(
          FieldConst.MimedData.ASSET_ID -> ujson.Str(mp.assetId),
          FieldConst.MimedData.MIME_TYPE -> ujson.Str(mp.mimeType),
          FieldConst.MimedData.DATA -> ujson.Str(Base64.encodeBase64String(mp.data))
        ),
      (json: ujson.Value) =>
        MimedPreviewData(
          assetId = json(FieldConst.MimedData.ASSET_ID).str,
          data = Base64.decodeBase64(json(FieldConst.MimedData.DATA).str)
        )
    )

  given Conversion[ujson.Value, MimedPreviewData] = json => JsonCodec.read[MimedPreviewData](json)

case class MimedPreviewData(assetId: String, data: Array[Byte]) extends BaseModel with NoId with NoDates:

  val mimeType: String = MimedPreviewData.MIME_TYPE

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
