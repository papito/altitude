package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

class MetadataController extends BaseApiController {

  get("/") {
    val allMetadataFields = app.service.metadata.getAllFields.values

    Ok(Json.obj(
      C.Api.Metadata.FIELDS -> JsArray(allMetadataFields.map(_.toJson).toSeq)
    ))
  }
}
