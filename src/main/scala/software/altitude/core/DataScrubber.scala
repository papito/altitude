package software.altitude.core

import play.api.libs.json.JsObject
import play.api.libs.json.Json

/**
 * API JSON payload data scrubber.
 *
 * @param trim a list of fields to be trimmed
 * @param lower a list of fields to be converted to lower case
 */
case class DataScrubber(trim: List[String] = List(),
                        lower: List[String] = List()) {

  /**
   * Scrubs the given JSON object by trimming and converting to lower case the specified fields.
   *
   * @param json the JSON object to be scrubbed
   * @return a new JSON object with the specified fields trimmed and converted to lower case
   */
  def scrub(json: JsObject): JsObject = {
    val trimmed = doTrim(json)
    val lowerCased = doLower(trimmed)
    lowerCased
  }

  /**
   * Trims the specified fields in the given JSON object.
   *
   * @param json the JSON object whose fields are to be trimmed
   * @return a new JSON object with the specified fields trimmed
   */
  private def doTrim(json: JsObject): JsObject = {
    json ++ trim.foldLeft(Json.obj()) { (res, field) =>
      (json \ field).asOpt[String] match {
        case v: Some[String] if v.nonEmpty => res ++ Json.obj(field -> v.get.trim)
        case _ => res
      }
    }
  }

  /**
   * Converts the specified fields in the given JSON object to lower case.
   *
   * @param json the JSON object whose fields are to be converted to lower case
   * @return a new JSON object with the specified fields converted to lower case
   */
  private def doLower(json: JsObject): JsObject = {
    json ++ lower.foldLeft(Json.obj()) { (res, field) =>
      (json \ field).asOpt[String] match {
        case v: Some[String] if v.nonEmpty => res ++ Json.obj(field -> v.get.toLowerCase)
        case _ => res
      }
    }
  }
}
