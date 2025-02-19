package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.controllers.BaseApiController

class MetadataController extends BaseApiController {

  get("/") {
    val allMetadataFields = app.service.metadata.getAllFields.values

    Ok(
      Json.obj(
        Api.Field.Metadata.FIELDS -> JsArray(allMetadataFields.map(_.toJson).toSeq)
      ))
  }
}
