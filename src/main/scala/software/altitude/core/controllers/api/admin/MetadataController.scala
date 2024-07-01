package software.altitude.core.controllers.api.admin
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.{Const => C}

class MetadataController extends BaseApiController {

  post("/") {
    val name = (unscrubbedReqJson.get \ C.Api.Metadata.Field.NAME).as[String]
    val fieldType = (unscrubbedReqJson.get \ C.Api.Metadata.Field.TYPE).as[String]
    logger.info(s"Adding metadata field [$name] of type [$fieldType]")

    val newField = MetadataField(name = name, fieldType = FieldType.withName(fieldType.toUpperCase))

    app.service.metadata.addField(newField)

    OK
  }

  delete("/:id") {
    val id = params.get(C.Api.ID).get
    logger.info(s"Deleting metadata field: $id")

    OK
  }

  put("/:id") {
    val id = params.get(C.Api.ID).get
    logger.info(s"Updating metadata field: $id")

    OK
  }

}
