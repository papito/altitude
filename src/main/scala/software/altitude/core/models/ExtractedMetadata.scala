package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions

object ExtractedMetadata {
  private type FieldValuesType = Map[String, String]
  private type MetadataType = Map[String, FieldValuesType]

  implicit def fromJson(json: JsValue): ExtractedMetadata = ExtractedMetadata(
    data = json.as[MetadataType]
  )
}

case class ExtractedMetadata(var data: ExtractedMetadata.MetadataType = Map[String, ExtractedMetadata.FieldValuesType]())
  extends BaseModel with NoId {
  /**
   * The raw extracted metadata is stored in a map of directories,
   * each containing a map of field/value pairs.
   *
   * This follows the pattern of MetadataExtractor:
   * https://github.com/drewnoakes/metadata-extractor/wiki/Getting-Started-(Java)
   *
   * Nikon Maker Note [Directory]
   * ** Firmware Version [Field] = 2.10 [Value]
   * ** ISO [Field] = ISO 125 [Value]
   */

  def addValue(directoryName: String, fieldName: String, value: String): Unit = {
    val directory = data.getOrElse(directoryName, Map())
    val updatedDirectory = directory + (fieldName -> value)
    data = data + (directoryName -> updatedDirectory)
  }

  def getFieldValues(directoryName: String): ExtractedMetadata.FieldValuesType = {
    data.getOrElse(directoryName, Map())
  }

  override def toJson: JsObject = {
    data.foldLeft(Json.obj())((acc, directory) => {
        val (directoryName, fields) = directory
        acc ++ Json.obj(directoryName -> fields)
    })
  }
}
