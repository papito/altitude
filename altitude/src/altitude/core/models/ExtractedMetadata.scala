package altitude.core.models

import ujson._
import ujson.Value
import upickle.default.ReadWriter
import upickle.default.write
import upickle.default.writeJs

import scala.language.implicitConversions

object ExtractedMetadata {
  private type FieldValuesType = Map[String, String]
  private type MetadataType = Map[String, FieldValuesType]

  def reads(json: Value): ExtractedMetadata = {
    val data = json.obj.map {
      case (key, value) =>
        key -> value.obj.map {
          case (fieldKey, fieldValue) =>
            fieldKey -> fieldValue.str
        }.toMap
    }.toMap
    ExtractedMetadata(data)
  }

  def writes(extractedMetadata: ExtractedMetadata): Value = {
    ujson.Obj.from(
      extractedMetadata.data.map {
        case (directoryName, fields) =>
          directoryName -> ujson.Obj.from(fields.map {
            case (key, value) =>
              key -> ujson.Str(value)
          })
      }
    )
  }

  implicit def fromJson(json: Value): ExtractedMetadata = reads(json)
}

case class ExtractedMetadata(var data: ExtractedMetadata.MetadataType = Map[String, ExtractedMetadata.FieldValuesType]())
  extends BaseModel
  with NoId
  with NoDates derives ReadWriter:
  def toJsonString: String = write(this)

  def toJson: Value = writeJs(this)

  /**
   * The raw extracted metadata is stored in a map of directories, each containing a map of field/value pairs.
   *
   * This follows the pattern of MetadataExtractor: https://github.com/drewnoakes/metadata-extractor/wiki/Getting-Started-(Java)
   *
   * Nikon Maker Note [Directory] ** Firmware Version [Field] = 2.10 [Value] ** ISO [Field] = ISO 125 [Value]
   */

  def addValue(directoryName: String, fieldName: String, value: String): Unit = {
    val directory = data.getOrElse(directoryName, Map())
    val updatedDirectory = directory + (fieldName -> value)
    data = data + (directoryName -> updatedDirectory)
  }

  def getFieldValues(directoryName: String): ExtractedMetadata.FieldValuesType = {
    data.getOrElse(directoryName, Map())
  }
