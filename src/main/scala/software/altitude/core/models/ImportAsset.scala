package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.Json

class ImportAsset(val fileName: String,
                  val data: Array[Byte],
                  val metadata: Metadata)
  extends BaseModel with NoId {

  override val toJson: JsObject = Json.obj(
    "fileName" -> fileName,
  )
}
