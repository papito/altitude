package altitude.core.models

import altitude.core.util.JsonCodec

object ExtractedMetadata:
  private type FieldValuesType = Map[String, String]
  private type MetadataType = Map[String, FieldValuesType]

  given JsonCodec.ReadWriter[ExtractedMetadata] = JsonCodec
    .readwriter[ujson.Value]
    .bimap(
      (em: ExtractedMetadata) => {
        val result = ujson.Obj()
        em.data.foreach {
          case (dirName, fields) =>
            val inner = ujson.Obj()
            fields.foreach { case (k, v) => inner(k) = ujson.Str(v) }
            result(dirName) = inner
        }
        result
      },
      (json: ujson.Value) =>
        ExtractedMetadata(
          json.obj.map {
            case (key, value) =>
              key -> value.obj.map { case (fieldKey, fieldValue) => fieldKey -> fieldValue.str }.toMap
          }.toMap
        )
    )

  given Conversion[ujson.Value, ExtractedMetadata] = json => JsonCodec.read[ExtractedMetadata](json)

case class ExtractedMetadata(var data: ExtractedMetadata.MetadataType = Map[String, ExtractedMetadata.FieldValuesType]())
  extends BaseModel
  with NoId
  with NoDates:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

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
