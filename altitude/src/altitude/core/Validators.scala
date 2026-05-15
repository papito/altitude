// src/main/scala/software/altitude/core/Validators.scala
package altitude.core

import altitude.core.Const as C

import scala.util.matching.Regex

object Validators {
  private val emailRegex: Regex =
    """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  private val uuidRegex: Regex = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r

  case class ApiRequestValidator(
      required: List[String] = List(),
      maxLengths: Map[String, Int] = Map.empty,
      minLengths: Map[String, Int] = Map.empty,
      email: List[String] = List.empty,
      uuid: List[String] = List.empty) {

    def validate(json: ujson.Obj): Unit = {
      val ex: ValidationException = ValidationException()

      required.foreach { field =>
        if !json.obj.contains(field) || json(field).strOpt.forall(_.isEmpty) then {
          ex.errors += (field -> C.Msg.Err.VALUE_REQUIRED)
        }
      }

      maxLengths.foreach {
        case (field, maxLength) =>
          if json.obj.contains(field) && json(field).strOpt.exists(_.length > maxLength) then {
            ex.errors += (field -> C.Msg.Err.VALUE_TOO_LONG.format(maxLength))
          }
      }

      minLengths.foreach {
        case (field, minLength) =>
          if json.obj.contains(field) && json(field).strOpt.exists(_.length < minLength) then {
            ex.errors += (field -> C.Msg.Err.VALUE_TOO_SHORT.format(minLength))
          }
      }

      email.foreach { field =>
        if isStillValid(ex, field, json) && json.obj.contains(field) &&
           !emailRegex.matches(json(field).str) then {
          ex.errors += (field -> C.Msg.Err.VALUE_NOT_AN_EMAIL)
        }
      }

      uuid.foreach { field =>
        if isStillValid(ex, field, json) &&
           !uuidRegex.matches(json(field).str) then {
          ex.errors += (field -> C.Msg.Err.VALUE_NOT_A_UUID)
        }
      }

      ex.trigger()
    }

    private def isStillValid(ex: ValidationException, field: String, json: ujson.Obj): Boolean = {
      !ex.errors.contains(field) && json.obj.contains(field)
    }
  }
}
