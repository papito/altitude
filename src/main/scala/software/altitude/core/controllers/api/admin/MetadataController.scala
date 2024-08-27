package software.altitude.core.controllers.api.admin
import software.altitude.core.Api
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField

class MetadataController extends BaseApiController {

  post("/") {
    val name = (unscrubbedReqJson.get \ Api.Field.Metadata.Field.NAME).as[String]
    val fieldType = (unscrubbedReqJson.get \ Api.Field.Metadata.Field.TYPE).as[String]
    logger.info(s"Adding metadata field [$name] of type [$fieldType]")

    val newField = MetadataField(name = name, fieldType = FieldType.withName(fieldType.toUpperCase))

    app.service.metadata.addField(newField)

    OK
  }

  delete("/:id") {
    val id = params.get(Api.Field.ID).get
    logger.info(s"Deleting metadata field: $id")

    OK
  }

  put("/:id") {
    val id = params.get(Api.Field.ID).get
    logger.info(s"Updating metadata field: $id")

    OK
  }

}
