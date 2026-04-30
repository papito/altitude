package altitude.core.models

import altitude.core.FieldConst
import org.apache.commons.codec.binary.Base64
import altitude.core.util.JsonCodec

object MimedFaceData:
  final val MIME_TYPE = "image/png"
  final val FILE_EXTENSION = "png"

  given JsonCodec.ReadWriter[MimedFaceData] = JsonCodec.readwriter[ujson.Value].bimap(
    (mf: MimedFaceData) => ujson.Obj(
      FieldConst.MimedData.MIME_TYPE -> ujson.Str(mf.mimeType),
      FieldConst.MimedData.DATA -> ujson.Str(Base64.encodeBase64String(mf.data))
    ),
    (json: ujson.Value) => MimedFaceData(
      data = Base64.decodeBase64(json(FieldConst.MimedData.DATA).str)
    )
  )

  given Conversion[ujson.Value, MimedFaceData] = json => JsonCodec.read[MimedFaceData](json)

case class MimedFaceData(data: Array[Byte]) extends BaseModel with NoId with NoDates:
  val mimeType: String = MimedFaceData.MIME_TYPE

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
