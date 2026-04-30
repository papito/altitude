package altitude.core.models

import altitude.core.util.JsonCodec

class ImportAsset(val fileName: String, val data: Array[Byte], val metadata: UserMetadata)
  extends BaseModel
  with NoId
  with NoDates:

  lazy val toJson: ujson.Obj = ujson.Obj("fileName" -> ujson.Str(fileName))
