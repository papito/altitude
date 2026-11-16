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

/**
 * Raw extracted metadata as metadata-extractor groups it: directory name to field name to value, e.g. `Nikon Maker Note` / `ISO` =
 * `ISO 125`
 */
case class ExtractedMetadata(var data: ExtractedMetadata.MetadataType = Map[String, ExtractedMetadata.FieldValuesType]())
  extends BaseModel
  with NoId
  with NoDates:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  def addValue(directoryName: String, fieldName: String, value: String): Unit =
    val directory = data.getOrElse(directoryName, Map())
    val updatedDirectory = directory + (fieldName -> value)
    data = data + (directoryName -> updatedDirectory)

  /** Keeps this metadata's fields (upstream synthetic ones) while `other`'s win within each directory; neither input changes */
  def merge(other: ExtractedMetadata): ExtractedMetadata =
    ExtractedMetadata(other.data.foldLeft(data) {
      case (merged, (directory, fields)) =>
        merged.updated(directory, merged.getOrElse(directory, Map.empty) ++ fields)
    })

  def getFieldValues(directoryName: String): ExtractedMetadata.FieldValuesType =
    data.getOrElse(directoryName, Map())
