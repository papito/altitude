// src/main/scala/software/altitude/core/Validators.scala
package software.altitude.core

import play.api.libs.json.JsObject
import software.altitude.core.{Const => C}

import scala.util.matching.Regex

object Validators {
  private val emailRegex: Regex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  private val uuidRegex: Regex = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r

  case class ApiRequestValidator(required: List[String] = List(),
                                 maxLengths: Map[String, Int] = Map.empty,
                                 minLengths: Map[String, Int] = Map.empty,
                                 email: List[String] = List.empty,
                                 uuid: List[String] = List.empty) {

    def validate(json: JsObject): Unit = {
      val ex: ValidationException = ValidationException()

      required.foreach { field =>
        if (!json.keys.contains(field) || json(field).as[String].isEmpty) {
          ex.errors += (field -> C.Msg.Err.VALUE_REQUIRED)
        }
      }

      maxLengths foreach { case (field, maxLength) =>
        if (json.keys.contains(field) && json(field).as[String].length > maxLength) {
          ex.errors += (field -> C.Msg.Err.VALUE_TOO_LONG.format(maxLength))
        }
      }

      minLengths foreach { case (field, minLength) =>
        if (json.keys.contains(field) && json(field).as[String].length < minLength) {
          ex.errors += (field -> C.Msg.Err.VALUE_TOO_SHORT.format(minLength))
        }
      }

      email.foreach { field =>
        if (isStillValid(ex, field, json) && json.keys.contains(field) &&
          !emailRegex.matches(json(field).as[String])) {
          ex.errors += (field -> C.Msg.Err.VALUE_NOT_AN_EMAIL)
        }
      }

      uuid.foreach { field =>
        if (isStillValid(ex, field, json) &&
          !uuidRegex.matches(json(field).as[String])) {
          ex.errors += (field -> C.Msg.Err.VALUE_NOT_A_UUID)
        }
      }

      ex.trigger()
    }

    private def isStillValid(ex: ValidationException, field: String, json: JsObject): Boolean = {
      !ex.errors.contains(field) && json.keys.contains(field)
    }
  }
}
