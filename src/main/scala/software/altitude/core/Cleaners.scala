package software.altitude.core

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

/**
 * Cleaners are used for data hygiene, before validation.
 *
 * Data - especially user data will come to us in a form that's not suitable to store
 * in its initial form, and it will happen a lot. But even though a leading space in
 * and email address is not what we want, it does not warrant a validation exception.
 */
object Cleaners {

  /**
   * Base version of the cleaner.
   *
   * @param trim fields to trim leading and trailing spaces from
   * @param lower fields to lowercase
   * @param defaults fields to set defaults for, if they are not given
   */
  case class Cleaner(trim: Option[List[String]] = None,
                     lower: Option[List[String]] = None,
                     defaults: Option[Map[String, String]] = None) {

    def clean(json: JsObject): JsObject = {
      val trimmed = doTrim(json)
      val wDefaults = doDefaults(trimmed)
      val lowerCased = doLower(wDefaults)
      lowerCased ++ Json.obj(C.Base.IS_CLEAN -> true)
    }

    protected def doTrim(json: JsObject): JsObject = {
      json ++ trim.getOrElse(List[String]()).foldLeft(Json.obj()) { (res, field) =>
        (json \ field.toString).asOpt[String] match {
          case v: Some[String] if v.nonEmpty => res ++ Json.obj(field.toString -> v.get.trim)
        }
      }
    }

    protected def doLower(json: JsObject): JsObject = {
      json ++ lower.getOrElse(List[String]()).foldLeft(Json.obj()) { (res, field) =>
        (json \ field.toString).asOpt[String] match {
          case v: Some[String] if v.nonEmpty => res ++ Json.obj(field.toString -> v.get.toLowerCase)
        }
      }
    }

    protected def doDefaults(json: JsObject): JsObject = {
      json ++ defaults.getOrElse(Map[String, String]()).keys.foldLeft(Json.obj()) { (res, field) =>
        (json \ field.toString).asOpt[String] match {
          case v: Some[String] if v.isEmpty => res ++ Json.obj(
            field.toString -> defaults.get(field.toString) )
        }
      }
    }
  }
}
