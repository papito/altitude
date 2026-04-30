package altitude.core

/**
 * API JSON payload data scrubber.
 *
 * @param trim
 *   a list of fields to be trimmed
 * @param lower
 *   a list of fields to be converted to lower case
 */
case class DataScrubber(trim: List[String] = List(), lower: List[String] = List()) {

  /**
   * Scrubs the given JSON object by trimming and converting to lower case the specified fields.
   *
   * @param json
   *   the JSON object to be scrubbed
   * @return
   *   a new JSON object with the specified fields trimmed and converted to lower case
   */
  def scrub(json: ujson.Obj): ujson.Obj = {
    doTrim(json)
    doLower(json)
    json
  }

  /**
   * Trims the specified fields in the given JSON object.
   *
   * @param json
   *   the JSON object whose fields are to be trimmed
   * @return
   *   a new JSON object with the specified fields trimmed
   */
  private def doTrim(json: ujson.Obj): Unit = {
    trim.foreach { field =>
      json.obj.get(field).flatMap(v => v.strOpt).filter(_.nonEmpty) match {
        case Some(v) => json(field) = v.trim
        case None =>
      }
    }
  }

  /**
   * Converts the specified fields in the given JSON object to lower case.
   *
   * @param json
   *   the JSON object whose fields are to be converted to lower case
   * @return
   *   a new JSON object with the specified fields converted to lower case
   */
  private def doLower(json: ujson.Obj): Unit = {
    lower.foreach { field =>
      json.obj.get(field).flatMap(v => v.strOpt).filter(_.nonEmpty) match {
        case Some(v) => json(field) = v.toLowerCase
        case None =>
      }
    }
  }
}
