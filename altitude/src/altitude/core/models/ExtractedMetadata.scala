package altitude.core.models

import play.api.libs.json._

object ExtractedMetadata:
  private type FieldValuesType = Map[String, String]
  private type MetadataType = Map[String, FieldValuesType]

  given reads: Reads[ExtractedMetadata] = (json: JsValue) =>
    val data = json
      .as[JsObject]
      .fields
      .map {
        case (key, value) =>
          key -> value
            .as[JsObject]
            .fields
            .map {
              case (fieldKey, fieldValue) =>
                fieldKey -> fieldValue.as[String]
            }
            .toMap
      }
      .toMap
    JsSuccess(ExtractedMetadata(data))

  given writes: OWrites[ExtractedMetadata] = (extractedMetadata: ExtractedMetadata) =>
    JsObject(
      extractedMetadata.data.map {
        case (directoryName, fields) =>
          directoryName -> JsObject(fields.map {
            case (key, value) =>
              key -> JsString(value)
          })
      }
    )

  given Conversion[JsValue, ExtractedMetadata] = json => Json.fromJson[ExtractedMetadata](json).get

case class ExtractedMetadata(var data: ExtractedMetadata.MetadataType = Map[String, ExtractedMetadata.FieldValuesType]())
  extends BaseModel
  with NoId
  with NoDates:

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  /**
   * The raw extracted metadata is stored in a map of directories, each containing a map of field/value pairs.
   *
   * This follows the pattern of MetadataExtractor: https://github.com/drewnoakes/metadata-extractor/wiki/Getting-Started-(Java)
   *
   * Nikon Maker Note [Directory] ** Firmware Version [Field] = 2.10 [Value] ** ISO [Field] = ISO 125 [Value]
   */

  def addValue(directoryName: String, fieldName: String, value: String): Unit =
    val directory = data.getOrElse(directoryName, Map())
    val updatedDirectory = directory + (fieldName -> value)
    data = data + (directoryName -> updatedDirectory)

  def getFieldValues(directoryName: String): ExtractedMetadata.FieldValuesType =
    data.getOrElse(directoryName, Map())
